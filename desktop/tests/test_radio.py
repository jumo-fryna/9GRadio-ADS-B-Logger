import ctypes as C
import threading
import time
import pytest
from PySide6.QtCore import QCoreApplication
from PySide6.QtTest import QTest
from skylog import radio
from test_core import iq_frame,EVEN


class FakeDriver:
    callback=C.CFUNCTYPE(None,C.POINTER(C.c_ubyte),C.c_uint32,C.c_void_p)
    def __init__(self,disconnect=False,fail=None):
        self.calls=[]; self.cancelled=threading.Event();self.disconnect=disconnect;self.fail=fail
    def devices(self):return [dict(product='MOCK V4L',index=0)]
    def open(self,ptr,index):ptr._obj.value=123;self.calls.append(('open',index));return 0
    def get_tuner_gains(self,dev,values):
        if values is not None:values[0]=0;values[1]=280;values[2]=386
        return 3
    def reset_buffer(self,dev):return 0
    def close(self,dev):self.calls.append(('close',));return 0
    def cancel_async(self,dev):self.cancelled.set();return 0
    def __getattr__(self,name):
        if not name.startswith('set_'):raise AttributeError(name)
        def setter(dev,value):
            self.calls.append((name,value));return -1 if name==self.fail else 0
        return setter
    def read_async(self,dev,callback,ctx,buffers,length):
        data=iq_frame(bytes.fromhex(EVEN));buf=(C.c_ubyte*len(data)).from_buffer_copy(data)
        callback(buf,len(data),None)
        if self.disconnect:return -4
        self.cancelled.wait(5);return 0


def wait_finished(worker):
    deadline=time.monotonic()+5
    while worker.isRunning() and time.monotonic()<deadline:
        QCoreApplication.processEvents();QTest.qWait(10)
    assert worker.wait(1000);QCoreApplication.processEvents()


@pytest.mark.parametrize('disconnect',[False,True])
def test_usb_pipeline_config_decode_stop_disconnect(monkeypatch,disconnect):
    app=QCoreApplication.instance()
    # QApplication is required later by UI tests; do not create QCoreApplication.
    from PySide6.QtWidgets import QApplication
    app=app or QApplication([])
    d=FakeDriver(disconnect);monkeypatch.setattr(radio,'RtlLibrary',lambda:d)
    w=radio.RadioWorker(0,38.6,0);batches=[];states=[]
    def receive(frames):
        batches.extend(frames)
        if not disconnect:w.stop()
    w.batch.connect(receive);w.status.connect(states.append);w.start();wait_finished(w)
    assert len(batches)==1 and batches[0].icao=='40621D'
    assert ('set_center_freq',1090000000) in d.calls and ('set_sample_rate',2000000) in d.calls
    assert ('set_tuner_gain',386) in d.calls and ('set_agc_mode',0) in d.calls and ('set_bias_tee',0) in d.calls
    assert d.calls[-1]==('close',)
    if disconnect:assert any('BŁĄD' in s and '-4' in s for s in states)


def test_usb_configuration_failure_closes_device(monkeypatch):
    from PySide6.QtWidgets import QApplication
    app=QApplication.instance() or QApplication([])
    d=FakeDriver(fail='set_sample_rate');monkeypatch.setattr(radio,'RtlLibrary',lambda:d)
    w=radio.RadioWorker();states=[];w.status.connect(states.append);w.start();wait_finished(w)
    assert any('set_sample_rate' in s for s in states);assert d.calls[-1]==('close',)
