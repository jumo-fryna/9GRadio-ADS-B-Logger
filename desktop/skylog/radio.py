"""Official librtlsdr C ABI; bounded queue separates USB from Python decoding."""
import ctypes as C
import os
from pathlib import Path
import queue
import threading
import time
from PySide6.QtCore import QThread, Signal
from .core import IqDecoder, FREQUENCY, SAMPLE_RATE

ROOT = Path(__file__).resolve().parent.parent


class RtlLibrary:
    def __init__(self, folder=None):
        self.folder = Path(folder or ROOT/'drivers').resolve()
        self.search = os.add_dll_directory(str(self.folder)) if os.name == 'nt' else None
        self.lib = C.CDLL(str(self.folder/'rtlsdr.dll'))
        self.callback = C.CFUNCTYPE(None, C.POINTER(C.c_ubyte), C.c_uint32, C.c_void_p)
        signatures = {
            'get_device_count': ([],C.c_uint32),
            'get_device_name': ([C.c_uint32], C.c_char_p),
            'get_device_usb_strings': ([C.c_uint32,C.c_char_p,C.c_char_p,C.c_char_p], C.c_int),
            'open': ([C.POINTER(C.c_void_p), C.c_uint32],C.c_int),
            'close': ([C.c_void_p],C.c_int),
            'get_tuner_type': ([C.c_void_p],C.c_int),
            'get_tuner_gains': ([C.c_void_p,C.POINTER(C.c_int)],C.c_int),
            'set_sample_rate': ([C.c_void_p,C.c_uint32],C.c_int),
            'set_center_freq': ([C.c_void_p,C.c_uint32],C.c_int),
            'set_direct_sampling': ([C.c_void_p,C.c_int],C.c_int),
            'set_tuner_gain_mode': ([C.c_void_p,C.c_int],C.c_int),
            'set_tuner_gain': ([C.c_void_p,C.c_int],C.c_int),
            'set_agc_mode': ([C.c_void_p,C.c_int],C.c_int),
            'set_freq_correction': ([C.c_void_p,C.c_int],C.c_int),
            'set_bias_tee': ([C.c_void_p,C.c_int],C.c_int),
            'reset_buffer': ([C.c_void_p],C.c_int),
            'read_async': ([C.c_void_p,self.callback,C.c_void_p,C.c_uint32,C.c_uint32],C.c_int),
            'cancel_async': ([C.c_void_p],C.c_int),
        }
        for name,(args,result) in signatures.items():
            fn=getattr(self.lib,'rtlsdr_'+name); fn.argtypes=args; fn.restype=result
            setattr(self,name,fn)

    def devices(self):
        rows=[]
        for i in range(self.get_device_count()):
            m,p,s=(C.create_string_buffer(256) for _ in range(3))
            rc=self.get_device_usb_strings(i,m,p,s)
            rows.append(dict(index=i,name=self.get_device_name(i).decode(errors='replace'),
                manufacturer=m.value.decode(errors='replace'),product=p.value.decode(errors='replace'),
                serial=s.value.decode(errors='replace'),strings_result=rc))
        return rows


class RadioWorker(QThread):
    batch = Signal(object)
    status = Signal(str)
    metrics = Signal(object)

    def __init__(self,index=0,gain=38.6,ppm=0):
        super().__init__()
        self.index=index; self.gain=gain; self.ppm=ppm
        self.stopping=threading.Event()
        self.device=C.c_void_p(); self.driver=None

    def stop(self):
        # USB owner thread performs cancellation, avoiding close/cancel races.
        self.stopping.set()

    def run(self):
        packets=queue.Queue(maxsize=64)
        drops=0
        errors=[]
        read_done=threading.Event()
        callback_error=[]
        decoder=IqDecoder()
        try:
            self.driver=d=RtlLibrary()
            devices=d.devices()
            if not devices:
                raise RuntimeError('Nie wykryto RTL-SDR. Podłącz V4L i sprawdź WinUSB (Zadig).')
            if d.open(C.byref(self.device),self.index) != 0:
                raise RuntimeError('Nie można otworzyć USB. Zamknij inne programy SDR i sprawdź WinUSB.')
            def checked(name,value):
                if getattr(d,name)(self.device,value) < 0:
                    raise RuntimeError('Błąd konfiguracji RTL-SDR: '+name)
            for name,value in [('set_direct_sampling',0),('set_sample_rate',SAMPLE_RATE),
                ('set_center_freq',FREQUENCY),('set_tuner_gain_mode',1),('set_agc_mode',0),('set_bias_tee',0)]:
                checked(name,value)
            if self.ppm: checked('set_freq_correction',self.ppm)
            n=d.get_tuner_gains(self.device,None)
            if not 0<n<256:
                raise RuntimeError('Sterownik nie zwrócił tabeli wzmocnień tunera.')
            gains=(C.c_int*n)(); d.get_tuner_gains(self.device,gains)
            gain=min(gains,key=lambda v:abs(v-self.gain*10)); checked('set_tuner_gain',gain)
            if d.reset_buffer(self.device)<0: raise RuntimeError('Nie można wyzerować bufora USB')
            @d.callback
            def callback(buf,length,ctx):
                nonlocal drops
                try:
                    if length and not self.stopping.is_set():
                        packets.put_nowait((time.time(),C.string_at(buf,length)))
                except queue.Full:
                    drops+=1
                except Exception as exc:
                    callback_error.append(str(exc)); self.stopping.set()
            def read():
                try:
                    rc=d.read_async(self.device,callback,None,12,65536)
                    if rc<0 and not self.stopping.is_set(): errors.append(f'USB read_async: {rc}')
                finally:
                    read_done.set()
            reader=threading.Thread(target=read,daemon=True); reader.start()
            self.status.emit(f"USB otwarte • {devices[self.index]['product']} • {gain/10:.1f} dB • oczekiwanie na IQ")
            last_iq=time.monotonic(); last_metrics=0; started=False
            while not self.stopping.is_set():
                if read_done.is_set() and packets.empty():
                    raise RuntimeError('; '.join(errors) or 'Strumień USB został zamknięty. Podłącz odbiornik ponownie.')
                try:
                    ts,data=packets.get(timeout=.2)
                    if drops:
                        decoder=IqDecoder(); raise RuntimeError('Przepełnienie kolejki IQ. Odbiór zatrzymano; zmniejsz obciążenie komputera.')
                    last_iq=time.monotonic()
                    if not started:
                        started=True; self.status.emit(f'IQ AKTYWNE • {devices[self.index]["product"]} • 1090 MHz • oczekiwanie na potwierdzone ADS-B')
                    frames=decoder.feed(data,ts)
                    if frames: self.batch.emit(frames)
                except queue.Empty:
                    if time.monotonic()-last_iq>5: raise RuntimeError('Brak danych IQ przez 5 sekund. Sprawdź USB i podłącz odbiornik ponownie.')
                if time.monotonic()-last_metrics>=1:
                    self.metrics.emit(dict(samples=decoder.samples,accepted=decoder.decoder.accepted,
                        rejected=decoder.decoder.rejected,drops=drops,queue=packets.qsize(),
                        preambles=decoder.preambles,candidates_adsb=decoder.candidates_adsb,**decoder.signal))
                    last_metrics=time.monotonic()
        except Exception as e:
            self.status.emit('BŁĄD • '+str(e))
        finally:
            if self.driver and self.device.value:
                self.stopping.set()
                if 'reader' in locals():
                    # Repeated cancellation covers stop-before-read_async startup.
                    while reader.is_alive():
                        self.driver.cancel_async(self.device); reader.join(.1)
                self.driver.close(self.device); self.device=C.c_void_p()


class LocationWorker(QThread):
    result=Signal(object)
    def run(self):
        import subprocess
        script = '''Add-Type -AssemblyName System.Device
        $w=New-Object System.Device.Location.GeoCoordinateWatcher
        try {
          $ok=$w.TryStart($false,[TimeSpan]::FromSeconds(10))
          if($ok -and -not $w.Position.Location.IsUnknown) {
            @{lat=$w.Position.Location.Latitude;lon=$w.Position.Location.Longitude;accuracy=$w.Position.Location.HorizontalAccuracy}|ConvertTo-Json -Compress
          } else { throw "Brak pozycji. Sprawdz zgode na lokalizacje Windows lub wpisz wspolrzedne recznie." }
        } finally {$w.Dispose()}'''
        try:
            import json
            p=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',script],
                capture_output=True,text=True,timeout=18,creationflags=0x08000000)
            if p.returncode: raise RuntimeError('Windows nie udostępnił pozycji. Włącz lokalizację i dostęp aplikacji klasycznych lub wpisz współrzędne ręcznie.')
            self.result.emit(json.loads(p.stdout))
        except Exception as exc:
            self.result.emit(dict(error=str(exc)))
