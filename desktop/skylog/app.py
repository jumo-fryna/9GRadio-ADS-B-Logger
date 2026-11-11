"""Native Qt Windows dashboard. No server, account or online map required."""
import json
import math
import os
from pathlib import Path
import sys
import time
from datetime import datetime, timezone, timedelta
from PySide6.QtCore import Qt, QTimer, QSettings, QDate, QUrl
from PySide6.QtGui import QColor, QPainter, QPen, QFont, QDesktopServices, QIcon
from PySide6.QtWidgets import (QApplication,QMainWindow,QWidget,QFrame,QVBoxLayout,QHBoxLayout,
    QLabel,QPushButton,QLineEdit,QComboBox,QTableWidget,QTableWidgetItem,QHeaderView,
    QSplitter,QStackedWidget,QFormLayout,QDoubleSpinBox,QSpinBox,QCheckBox,QMessageBox,
    QFileDialog,QDateEdit,QAbstractItemView,QScrollArea,QGridLayout)
from . import __version__
from .core import Frame, Tracker, Decoder, valid_position, distance_nm
from .storage import Store, export_report, utc
from .radio import RadioWorker, LocationWorker, RtlLibrary
from .radar import Radar, Dipole

STYLE='''
QWidget { background:#0b1220; color:#dce6ef; font-family:"Segoe UI"; font-size:13px; }
QMainWindow {background:#0b1220;}
QFrame#sidebar {background:#101b2b;border-right:1px solid #243247;}
QFrame#sidebar QLabel {background:transparent;}
QFrame#card {background:#121f30;border:1px solid #25384b;border-radius:10px;}
QFrame#card QLabel {background:transparent;}
QFrame#card QCheckBox {background:transparent;}
QLabel#muted {color:#8298ac;font-size:12px;}
QLabel#title {font-size:26px;font-weight:700;}
QLabel#metric {font-size:29px;font-weight:600;color:#e6f4fa;}
QLabel#brand {font-size:23px;font-weight:700;color:#4fe0c3;}
QPushButton {background:#182a3d;border:1px solid #2c4358;border-radius:7px;padding:10px 16px;font-weight:600;}
QPushButton:hover {background:#253e54;border-color:#4fe0c3;}
QPushButton:checked {background:#1d3e4a;color:#62ead0;border-color:#2c8a81;}
QPushButton#primary {background:#42d4ba;color:#09242a;border:0;}
QPushButton#primary:hover {background:#74f0d8;}
QPushButton:disabled {color:#536a7c;background:#18222f;border-color:#243244;}
QPushButton#primary:disabled {color:#708795;background:#1b3c43;}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox,QDateEdit {background:#101c2b;border:1px solid #2b4055;border-radius:6px;padding:8px;selection-background-color:#217d7b;}
QTableWidget {background:#0f1a29;alternate-background-color:#132132;gridline-color:#243348;border:1px solid #243348;border-radius:6px;selection-background-color:#214b57;}
QTableWidget::item {padding:7px;border:0;}
QHeaderView::section {background:#162638;color:#8ba4b7;border:0;padding:10px;font-size:11px;font-weight:600;}
QScrollArea {border:0;}
QScrollBar:vertical {background:#111d2c;width:10px;}
QScrollBar::handle:vertical {background:#355065;border-radius:4px;min-height:24px;}
QSplitter::handle {background:#1d3042;}
QToolTip {background:#21384b;color:#e3edf3;border:1px solid #4fe0c3;padding:6px;}
'''


def label(text,kind=None):
    w=QLabel(text)
    if kind: w.setObjectName(kind)
    return w


def button(text,callback,primary=False):
    w=QPushButton(text); w.clicked.connect(callback)
    if primary: w.setObjectName('primary')
    return w


def card(title,value='—',sub=''):
    w=QFrame(); w.setObjectName('card'); layout=QVBoxLayout(w)
    layout.setContentsMargins(18,14,18,14)
    layout.addWidget(label(title,'muted')); metric=label(value,'metric'); layout.addWidget(metric)
    layout.addWidget(label(sub,'muted'))
    return w,metric


def table(headers):
    t=QTableWidget(0,len(headers)); t.setHorizontalHeaderLabels(headers)
    t.verticalHeader().hide(); t.setAlternatingRowColors(True)
    t.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
    t.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
    t.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
    t.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
    t.verticalHeader().setDefaultSectionSize(38)
    return t


class NumberItem(QTableWidgetItem):
    def __init__(self, text, value=None):
        super().__init__(text); self.value=value
    def __lt__(self,other):
        if isinstance(other,NumberItem):
            return (self.value if self.value is not None else -math.inf)<(other.value if other.value is not None else -math.inf)
        return super().__lt__(other)


class HourChart(QWidget):
    def __init__(self):
        super().__init__(); self.setMinimumHeight(190); self.values=[0]*24
    def paintEvent(self,e):
        p=QPainter(self); p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.fillRect(self.rect(),QColor('#101e2d'))
        p.setPen(QColor('#8ba5b8')); p.drawText(20,26,'PIERWSZE WYKRYCIA UNIKALNYCH ICAO / GODZINA UTC')
        maximum=max(1,max(self.values)); width=(self.width()-60)/24; baseline=self.height()-34
        p.setPen(QPen(QColor('#284154'),1)); p.drawLine(30,baseline,self.width()-20,baseline)
        for i,n in enumerate(self.values):
            h=(self.height()-82)*n/maximum
            p.fillRect(int(32+i*width),int(baseline-h),max(2,int(width-6)),int(h),QColor('#48cfba'))
            if i%3==0:
                p.setPen(QColor('#829bad')); p.drawText(int(32+i*width),baseline+20,f'{i:02}')
            if n:
                p.drawText(int(32+i*width),int(baseline-h-5),str(n))


class Window(QMainWindow):
    def __init__(self,data_dir=None):
        super().__init__(); self.setWindowTitle('SkyLog 1090 • Windows'); self.resize(1560,960)
        self.setWindowIcon(QIcon(str(Path(__file__).resolve().parent.parent/'assets'/'skylog.ico')))
        self.setMinimumSize(1120,760)
        self.settings=QSettings('SkyLog1090','Windows')
        self.data_dir=Path(data_dir or Path(os.environ.get('LOCALAPPDATA',str(Path.home())))/'SkyLog1090')
        self.data_dir.mkdir(parents=True,exist_ok=True)
        self.real_store=Store(str(self.data_dir/'skylog1090.sqlite'))
        self.demo_store=Store(':memory:'); self.store=self.real_store
        self.tracker=Tracker(); self.worker=None; self.location_worker=None
        self.mode='IDLE'; self.selected=''; self.position=None; self.report_rows=[]; self.report_at=None
        self.last_total=0; self.last_rate=time.monotonic(); self.demo_step=0; self.replay_lines=[]
        self.last_day=datetime.now(timezone.utc).date()
        root=QWidget(); self.setCentralWidget(root); outer=QHBoxLayout(root); outer.setContentsMargins(0,0,0,0); outer.setSpacing(0)
        side=QFrame(); side.setObjectName('sidebar'); side.setFixedWidth(218); sl=QVBoxLayout(side); sl.setContentsMargins(20,26,20,22)
        sl.addWidget(label('◉  SKYLOG','brand')); sl.addWidget(label('1090  /  WINDOWS','muted')); sl.addSpacing(32)
        self.pages=QStackedWidget(); self.nav=[]
        for i,text in enumerate(['◉   Radar LIVE','▤   Historia','▥   Raport','⚙   Odbiornik','⌁   Antena i pozycja']):
            b=button(text,lambda checked=False,index=i:self.navigate(index)); b.setCheckable(True); self.nav.append(b); sl.addWidget(b)
        sl.addStretch(); sl.addWidget(label('LOKALNY ODBIÓR\nBez konta i map online','muted'))
        sl.addSpacing(20); sl.addWidget(label(f'MVP {__version__}  •  x64','muted')); outer.addWidget(side)
        main=QWidget(); ml=QVBoxLayout(main); ml.setContentsMargins(26,22,26,18); ml.setSpacing(16)
        header=QHBoxLayout(); hl=QVBoxLayout(); hl.addWidget(label('Twój obraz nieba.','title'))
        hl.addWidget(label('ADS-B 1090 MHz   /   lokalny odbiór i historia   /   wysokość ft · prędkość kt · dystans NM','muted')); header.addLayout(hl); header.addStretch()
        self.clock=label('','muted'); header.addWidget(self.clock); ml.addLayout(header)
        statusbar=QHBoxLayout(); self.status=label('● GOTOWY • odbiór zatrzymany'); self.status.setWordWrap(True)
        statusbar.addWidget(self.status,1)
        self.start_button=button('▶  Uruchom RTL-SDR',self.start_radio,True); statusbar.addWidget(self.start_button)
        self.stop_button=button('■  Zatrzymaj',self.stop); self.stop_button.setEnabled(False); statusbar.addWidget(self.stop_button)
        self.demo_button=button('Podgląd DEMO',self.start_demo); statusbar.addWidget(self.demo_button); ml.addLayout(statusbar)
        self.build_live(); self.build_history(); self.build_report(); self.build_receiver(); self.build_antenna()
        ml.addWidget(self.pages,1)
        self.footer=label('SQLite • zapis lokalny   |   Pozycja odbiornika: nieustawiona','muted'); ml.addWidget(self.footer)
        outer.addWidget(main,1); self.navigate(0)
        self.timer=QTimer(self); self.timer.timeout.connect(self.tick); self.timer.start(1000)
        self.demo_timer=QTimer(self); self.demo_timer.timeout.connect(self.demo_tick)
        self.replay_timer=QTimer(self); self.replay_timer.timeout.connect(self.replay_tick)
        self.restore_position(); self.tick()

    def build_live(self):
        page=QWidget(); l=QVBoxLayout(page); l.setContentsMargins(0,0,0,0); l.setSpacing(14)
        metrics=QHBoxLayout(); self.metrics=[]; self.metric_subs=[]
        for title,sub in [('SAMOLOTY LIVE','widziane w ostatnich 120 s'),('RAMKI ADS-B','CRC OK • bieżąca sesja'),('TEMPO ODBIORU','zaakceptowane ramki / s'),('ZASIĘG LIVE','od pozycji odbiornika')]:
            w,m=card(title,'0' if len(self.metrics)<2 else '—',sub); self.metrics.append(m); self.metric_subs.append(w.layout().itemAt(2).widget()); metrics.addWidget(w)
        l.addLayout(metrics)
        tools=QHBoxLayout(); self.search=QLineEdit(); self.search.setPlaceholderText('Szukaj ICAO lub callsign…'); self.search.textChanged.connect(self.refresh_live); tools.addWidget(self.search,1)
        self.position_only=QCheckBox('Tylko z pozycją'); self.position_only.toggled.connect(self.refresh_live); tools.addWidget(self.position_only)
        self.min_alt=QSpinBox(); self.min_alt.setRange(-2000,60000); self.min_alt.setValue(-2000); self.min_alt.setSingleStep(1000); self.min_alt.setPrefix('Min ft: '); self.min_alt.valueChanged.connect(self.refresh_live); tools.addWidget(self.min_alt)
        tools.addWidget(button('Wyśrodkuj',lambda:self.radar.reset())); l.addLayout(tools)
        horizontal=QSplitter(Qt.Orientation.Horizontal); vertical=QSplitter(Qt.Orientation.Vertical)
        self.radar=Radar(); self.radar.selected.connect(self.select); vertical.addWidget(self.radar)
        self.live_table=table(['ICAO / CALLSIGN','WYS. ft','PRĘDK. kt','KURS °','DYST. NM','WIEK s','RAMKI'])
        self.live_table.setSortingEnabled(True); self.live_table.itemSelectionChanged.connect(self.table_selected)
        vertical.addWidget(self.live_table); vertical.setSizes([410,220]); horizontal.addWidget(vertical)
        detail=QFrame(); detail.setObjectName('card'); detail.setMinimumWidth(230); detail.setMaximumWidth(310)
        dl=QVBoxLayout(detail); dl.setContentsMargins(20,20,20,20); dl.addWidget(label('WYBRANY SAMOLOT','muted'))
        self.detail_title=label('—','title'); dl.addWidget(self.detail_title)
        self.detail=label('Wybierz samolot na radarze\nlub w tabeli.'); self.detail.setWordWrap(True); self.detail.setTextFormat(Qt.TextFormat.PlainText); dl.addWidget(self.detail)
        dl.addStretch(); dl.addWidget(label('POZYCJE Z TRANSMISJI ADS-B\nSzare znaczniki: pozycja >30 s.\nNieznane parametry: —','muted'))
        dl.addWidget(button('Historia tego ICAO',self.history_selected)); horizontal.addWidget(detail); horizontal.setSizes([1000,250]); l.addWidget(horizontal,1)
        self.pages.addWidget(page)

    def history_controls(self,layout):
        bar=QHBoxLayout(); self.history_search=QLineEdit(); self.history_search.setPlaceholderText('Filtr: ICAO / callsign'); bar.addWidget(self.history_search,1)
        self.all_dates=QCheckBox('Cała historia'); self.all_dates.setChecked(True); bar.addWidget(self.all_dates)
        self.from_date=QDateEdit(QDate.currentDate()); self.to_date=QDateEdit(QDate.currentDate())
        for w in (self.from_date,self.to_date): w.setCalendarPopup(True); w.setDisplayFormat('yyyy-MM-dd'); bar.addWidget(w)
        bar.addWidget(button('Zastosuj',self.refresh_history)); layout.addLayout(bar)
        for w in (self.from_date,self.to_date): w.setEnabled(False)
        self.all_dates.toggled.connect(lambda checked:[w.setEnabled(not checked) for w in (self.from_date,self.to_date)])

    def build_history(self):
        page=QWidget(); l=QVBoxLayout(page); l.setContentsMargins(0,0,0,0)
        l.addWidget(label('Historia odbioru','title')); l.addWidget(label('Wizyty rozdziela przerwa 5 minut. Daty i godziny w UTC. Filtr dotyczy nakładających się wizyt.','muted'))
        self.history_controls(l)
        self.history_table=table(['ICAO','CALLSIGN','PIERWSZY ODBIÓR UTC','OSTATNI ODBIÓR UTC','RAMKI','MAX ft','MAX kt','ŹRÓDŁO'])
        self.history_table.itemDoubleClicked.connect(self.history_detail); l.addWidget(self.history_table,1)
        bottom=QHBoxLayout(); self.history_count=label('0 wizyt','muted'); bottom.addWidget(self.history_count); bottom.addStretch()
        bottom.addWidget(button('Eksportuj widoczne…',self.export_history)); bottom.addWidget(button('Utwórz raport',self.capture_report)); l.addLayout(bottom)
        self.pages.addWidget(page)

    def build_report(self):
        page=QWidget(); l=QVBoxLayout(page); l.setContentsMargins(0,0,0,0); l.setSpacing(18)
        l.addWidget(label('Raport odbioru','title')); self.report_caption=label('Utwórz raport z przefiltrowanej historii.','muted'); self.report_caption.setWordWrap(True); l.addWidget(self.report_caption)
        cards=QHBoxLayout(); self.report_metrics=[]
        for title in ('UNIKALNE ICAO','WIZYTY','RAMKI ADS-B','MAKS. ZASIĘG NM'):
            w,m=card(title); self.report_metrics.append(m); cards.addWidget(w)
        l.addLayout(cards); self.chart=HourChart(); l.addWidget(self.chart,1)
        l.addWidget(label('Ranking odbioru','muted')); self.rank=table(['ICAO','CALLSIGN','RAMKI','MAX ft','MAX kt','MAX NM']); l.addWidget(self.rank,1)
        row=QHBoxLayout(); row.addWidget(button('Odśwież z filtrów historii',self.capture_report)); row.addStretch()
        for fmt in ('CSV','XLSX','PDF'): row.addWidget(button('Eksport '+fmt,lambda checked=False,f=fmt:self.export_snapshot(f)))
        l.addLayout(row); self.pages.addWidget(page)

    def build_receiver(self):
        page=QWidget(); l=QVBoxLayout(page); l.setContentsMargins(0,0,0,0)
        l.addWidget(label('Odbiornik i diagnostyka','title'))
        text=label('Profil RTL-SDR Blog V4L • RTL2832U + tuner R828S\nSprzęt: 500 kHz–1,766 GHz • 8 bit • SMA 50 Ω • TCXO 1 ppm • stabilne pasmo 2,56 MHz\nOficjalna biblioteka RTL-SDR Blog V1.4.0 x64 dołączona do aplikacji.\nProfil ADS-B: 1090 MHz / 2 000 000 próbek IQ/s / 8-bit I + 8-bit Q\nDirect sampling: OFF • IF/RTL AGC: OFF • Bias-T: OFF'); text.setWordWrap(True); l.addWidget(text)
        form=QFormLayout(); self.devices=QComboBox(); form.addRow('Urządzenie USB',self.devices)
        self.gain=QDoubleSpinBox(); self.gain.setRange(0,49.6); self.gain.setDecimals(1); self.gain.setValue(float(self.settings.value('gain',38.6))); self.gain.setSuffix(' dB')
        form.addRow('Wzmocnienie (najbliższy krok tunera)',self.gain)
        self.ppm=QSpinBox(); self.ppm.setRange(-200,200); self.ppm.setValue(int(self.settings.value('ppm',0))); form.addRow('Korekcja PPM',self.ppm)
        l.addLayout(form); row=QHBoxLayout(); self.scan_button=button('Szukaj odbiorników',self.scan_devices); row.addWidget(self.scan_button)
        row.addWidget(button('Instrukcja WinUSB / V4L',self.driver_help)); row.addStretch(); l.addLayout(row)
        self.rf_metrics={}; self.rf_cards=[]
        grid=QGridLayout()
        for i,(title,sub) in enumerate([('PRÓBKI IQ','od początku odbioru'),('MOC ŚREDNIA','dBFS • ostatni blok ADC'),('PRZESTEROWANIE','% próbek blisko skraju ADC'),('KANDYDACI PREAMBUŁY','obejmują również szum'),('KANDYDACI ADS-B','rozpoznany format DF17/18'),('ODRZUCONE CRC','nie trafiają do historii')]):
            w,m=card(title,'—',sub);self.rf_cards.append(m);grid.addWidget(w,i//3,i%3)
        l.addLayout(grid)
        self.diagnostics=label('Nie sprawdzono jeszcze urządzeń.'); self.diagnostics.setWordWrap(True); l.addWidget(self.diagnostics)
        l.addWidget(label('dBFS opisuje poziom cyfrowych próbek, a nie skalibrowaną moc na wejściu anteny. Otwarty USB i kandydaci preambuły nie potwierdzają odbioru ADS-B.','muted'))
        l.addWidget(button('Zapisz diagnostykę odbioru…',self.export_diagnostics))
        l.addWidget(label('Parametry stosowane przy następnym uruchomieniu. Program nie zmienia EEPROM odbiornika.','muted'))
        l.addSpacing(20); l.addWidget(label('Odtwarzanie danych','title'))
        l.addWidget(label('Plik AVR: jedna ramka *HEX; lub HEX w wierszu. CRC sprawdzane tym samym dekoderem.\nOdtwarzanie 50 ramek/s; czas odtworzenia stanowi czas odbioru. Dane oznaczone REPLAY.','muted'))
        self.replay_button=button('Otwórz plik AVR…',self.open_replay); l.addWidget(self.replay_button)
        l.addStretch(); l.addWidget(button('Otwórz folder danych',lambda:QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.data_dir)))))
        scroll=QScrollArea();scroll.setWidgetResizable(True);scroll.setWidget(page);self.pages.addWidget(scroll)

    def build_antenna(self):
        page=QWidget(); l=QVBoxLayout(page); l.setContentsMargins(0,0,0,0)
        l.addWidget(label('Antena i lokalizacja','title'))
        splitter=QSplitter(Qt.Orientation.Horizontal); left=QWidget(); a=QVBoxLayout(left)
        a.addWidget(Dipole()); tips=label('Ustaw dwa ramiona w jednej linii pionowej: jedno do góry, drugie w dół.\n\nDla 1090 MHz: λ = c/f ≈ 27,5 cm. Punkt startowy ramienia λ/4 to 6,9 cm; cały dipol ≈ 13,8 cm. To przybliżenie — uchwyt i grubość pręta wpływają na strojenie.\n\nJeśli zestaw ma ramiona teleskopowe, ustaw najkrótsze możliwe ramiona blisko tej długości. Nie docinaj elementów bez pomiaru.\n\nUstaw antenę wysoko i blisko okna, z odsłoniętym horyzontem. Odsuń ją od laptopa, zasilacza i metalowych przeszkód. Obrót wokół pionu zwykle ma mniejsze znaczenie niż wysokość i przesłonięcie.\n\nWybierz ten sam gain i porównaj po 10–15 minut: ramki/s, liczbę samolotów i zasięg. Lokalizacja nie wskazuje jednego kierunku ustawienia dipola — odbiór dookólny zależy od otoczenia.'); tips.setWordWrap(True); a.addWidget(tips); a.addStretch()
        antenna_scroll=QScrollArea(); antenna_scroll.setWidgetResizable(True); antenna_scroll.setWidget(left); splitter.addWidget(antenna_scroll)
        right=QFrame(); right.setObjectName('card'); rl=QVBoxLayout(right); rl.setContentsMargins(22,22,22,22)
        rl.addWidget(label('POZYCJA ODBIORNIKA','muted')); rl.addWidget(label('Centrum Twojego radaru','title'))
        note=label('Pozycja służy do obliczania odległości i środka mapy. Jest przechowywana lokalnie. Windows może zwrócić przybliżoną lokalizację Wi-Fi; Legion zwykle nie ma GPS.'); note.setWordWrap(True); rl.addWidget(note)
        self.position_known=QCheckBox('Używaj podanych współrzędnych'); rl.addWidget(self.position_known)
        form=QFormLayout(); self.latitude=QDoubleSpinBox(); self.latitude.setRange(-90,90); self.latitude.setDecimals(6)
        self.longitude=QDoubleSpinBox(); self.longitude.setRange(-180,180); self.longitude.setDecimals(6)
        form.addRow('Szerokość °',self.latitude); form.addRow('Długość °',self.longitude); rl.addLayout(form)
        self.save_position_button=button('Zapisz lokalizację',self.save_position,True); rl.addWidget(self.save_position_button)
        self.locate_button=button('Pobierz pozycję z Windows',self.locate); rl.addWidget(self.locate_button)
        rl.addWidget(button('Ustawienia lokalizacji Windows',lambda:QDesktopServices.openUrl(QUrl('ms-settings:privacy-location'))))
        self.location_status=label('Lokalizacja nie została pobrana.','muted'); self.location_status.setWordWrap(True); rl.addWidget(self.location_status); rl.addStretch()
        splitter.addWidget(right); splitter.setSizes([600,480]); l.addWidget(splitter,1); self.pages.addWidget(page)

    def navigate(self,index):
        self.pages.setCurrentIndex(index)
        for i,b in enumerate(self.nav): b.setChecked(i==index)
        if index==1: self.refresh_history()

    def restore_position(self):
        raw=self.settings.value('position','')
        try:
            pos=json.loads(raw)
            if valid_position(*pos):
                self.position=tuple(pos); self.position_known.setChecked(True)
                self.latitude.setValue(pos[0]); self.longitude.setValue(pos[1]); self.radar.receiver=self.position; self.radar.reset()
        except (ValueError,TypeError): pass

    def save_position(self):
        self.position=(self.latitude.value(),self.longitude.value()) if self.position_known.isChecked() else None
        self.settings.setValue('position',json.dumps(self.position)); self.radar.receiver=self.position; self.radar.reset()
        self.location_status.setText('Zapisano lokalnie.' if self.position else 'Pozycja wyłączona. Odległości pozostają nieznane.')
        self.refresh_live()

    def locate(self):
        if self.location_worker and self.location_worker.isRunning(): return
        self.locate_button.setEnabled(False); self.location_status.setText('Windows ustala pozycję…')
        self.location_worker=LocationWorker(); self.location_worker.result.connect(self.location_result)
        self.location_worker.finished.connect(lambda:self.locate_button.setEnabled(True)); self.location_worker.start()

    def location_result(self,result):
        if 'error' in result: self.location_status.setText(result['error']); return
        if not valid_position(result.get('lat'),result.get('lon')):
            self.location_status.setText('Windows zwrócił nieprawidłowe współrzędne.'); return
        self.latitude.setValue(result['lat']); self.longitude.setValue(result['lon']); self.position_known.setChecked(True)
        if self.mode!='IDLE':
            self.location_status.setText('Pozycja pobrana. Po zatrzymaniu odbioru kliknij Zapisz lokalizację.'); return
        self.save_position(); self.location_status.setText(f"Pozycja z Windows. Dokładność: {result.get('accuracy','?')} m. Możesz skorygować współrzędne ręcznie.")

    def scan_devices(self):
        try:
            devices=RtlLibrary().devices(); self.devices.clear()
            for d in devices: self.devices.addItem(f"{d['index']}: {d['product']}  SN {d['serial']}",d['index'])
            if not devices: self.devices.addItem('Brak wykrytych odbiorników',0)
            self.diagnostics.setText(f'Biblioteka V1.4.0 x64 załadowana. Odbiorników: {len(devices)}.\n'+json.dumps(devices,ensure_ascii=False))
        except Exception as exc:
            self.diagnostics.setText('Nie można załadować sterownika: '+str(exc))

    def driver_help(self):
        QMessageBox.information(self,'RTL-SDR Blog V4L • sterowniki','Biblioteka rtlsdr.dll V1.4.0 x64 i zależności są w paczce.\n\n1. Podłącz odbiornik i zamknij inne programy SDR.\n2. Pobierz Zadig z https://zadig.akeo.ie/.\n3. Options → List All Devices. Wybierz RTL-SDR Blog V4L / Bulk-In Interface 0. Sprawdź tożsamość odbiornika.\n4. Przypisz WinUSB tylko temu interfejsowi. Nie zmieniaj sterownika Wi-Fi, klawiatury ani innego urządzenia.\n5. Wróć i kliknij Szukaj odbiorników.\n\nPrzypisanie WinUSB może wymagać uprawnień administratora. Aplikacja działa jako zwykły użytkownik.\nInstrukcja producenta: https://www.rtl-sdr.com/v4l/')

    def show_rf_metrics(self,m):
        self.rf_metrics=dict(m)
        power=m.get('power_dbfs');clipped=m.get('clipped_percent',0)
        values=[f"{m['samples']/1e6:.1f} mln",f'{power:.1f}' if power is not None else '—',f'{clipped:.3f}%',str(m.get('preambles',0)),str(m.get('candidates_adsb',0)),str(m['rejected'])]
        for card_label,value in zip(self.rf_cards,values):card_label.setText(value)
        if m['accepted']:
            text=f"Potwierdzone ADS-B: {m['accepted']} ramek z poprawnym CRC. USB działa; straty bloków: {m['drops']}."
        elif clipped>1:
            text='Dane IQ docierają, ale wiele próbek osiąga skraj zakresu ADC. Zatrzymaj odbiór, zmniejsz gain i porównaj wynik.'
        else:
            text='Dane IQ docierają, ale brak ramek ADS-B z poprawnym CRC. To nie jest problem filtra mapy ani ustawienia lokalizacji. Porównaj gain 38,6 i 49,6 dB oraz otoczenie anteny; same kandydatury mogą pochodzić z szumu.'
        self.diagnostics.setText(text)
        if self.mode=='LIVE' and self.worker and not self.worker.stopping.is_set() and not self.status.text().startswith('BŁĄD'):
            self.status.setText(f"{'LIVE ADS-B • CRC OK' if m['accepted'] else 'IQ AKTYWNE • brak potwierdzonych ramek ADS-B'} • 1090 MHz • 2 MS/s")

    def export_diagnostics(self):
        path,_=QFileDialog.getSaveFileName(self,'Zapisz diagnostykę',str(self.data_dir/'SkyLog-diagnostyka.json'),'JSON (*.json)')
        if not path:return
        try:
            Path(path).write_text(json.dumps(dict(version=__version__,utc=utc(time.time()),metrics=self.rf_metrics,status=self.status.text(),gain_requested_db=self.gain.value(),ppm=self.ppm.value()),indent=2,ensure_ascii=False),encoding='utf8')
        except Exception as exc:QMessageBox.warning(self,'Zapis diagnostyki',str(exc))

    def set_running(self,running):
        for b in (self.start_button,self.demo_button,self.replay_button,self.scan_button): b.setEnabled(not running)
        for w in (self.gain,self.ppm,self.devices,self.save_position_button,self.locate_button): w.setEnabled(not running)
        self.stop_button.setEnabled(running)

    def begin(self,mode):
        self.mode=mode; self.tracker=Tracker(); self.selected=''; self.radar.selected_icao=''
        self.store=self.demo_store if mode=='DEMO' else self.real_store
        self.store.start(mode,self.position); self.last_total=0; self.last_rate=time.monotonic()
        self.last_day=datetime.now(timezone.utc).date(); self.set_running(True)
        self.rf_metrics={}
        for m in self.rf_cards:m.setText('—')
        self.metric_subs[1].setText('DEMO • aktualizacje syntetyczne' if mode=='DEMO' else 'CRC OK • bieżący odbiór')
        self.report_rows=[]; self.report_at=None
        self.report_caption.setText('Nowe źródło danych. Utwórz raport z filtrów historii.')
        for m in self.report_metrics:m.setText('—')
        self.rank.setRowCount(0); self.chart.values=[0]*24; self.chart.update()

    def start_radio(self):
        if self.worker or self.mode!='IDLE': return
        self.settings.setValue('gain',self.gain.value()); self.settings.setValue('ppm',self.ppm.value())
        self.begin('LIVE'); self.status.setText('Łączenie z RTL-SDR…')
        self.worker=RadioWorker(self.devices.currentData() or 0,self.gain.value(),self.ppm.value())
        self.worker.batch.connect(self.accept); self.worker.status.connect(self.status.setText)
        self.worker.metrics.connect(self.show_rf_metrics)
        self.worker.finished.connect(self.radio_finished); self.worker.start()

    def radio_finished(self):
        self.store.stop(); self.worker.deleteLater(); self.worker=None; self.mode='IDLE'; self.set_running(False)
        self.status.setText(self.status.text()+' • odbiór zakończony' if self.status.text().startswith('BŁĄD') else '● ZATRZYMANO • historia zapisana lokalnie'); self.refresh_live()

    def stop(self):
        self.demo_timer.stop(); self.replay_timer.stop()
        if self.worker:
            self.worker.stop(); self.stop_button.setEnabled(False); self.status.setText('Zatrzymywanie strumienia USB…'); return
        self.store.stop(); self.mode='IDLE'; self.set_running(False); self.status.setText('● ZATRZYMANO • historia zapisana lokalnie'); self.refresh_live()

    def accept(self,frames):
        if self.mode=='IDLE': return
        day=datetime.now(timezone.utc).date()
        if day!=self.last_day:
            self.store.start(self.mode,self.position); self.last_day=day
        for f in frames:
            a=self.tracker.accept(f)
            distance=distance_nm(*self.position,f.latitude,f.longitude) if self.position and valid_position(f.latitude,f.longitude) else None
            self.store.accept(f,a,distance)

    def tick(self):
        self.clock.setText(datetime.now(timezone.utc).strftime('%H:%M:%S UTC\n%d.%m.%Y'))
        now=time.monotonic(); elapsed=now-self.last_rate
        if elapsed>=.8:
            self.metrics[2].setText(f'{(self.tracker.total-self.last_total)/elapsed:.1f} /s')
            self.last_total=self.tracker.total; self.last_rate=now
        try:
            self.store.flush()
        except Exception as exc:
            self.stop(); self.status.setText('BŁĄD ZAPISU • '+str(exc))
        self.refresh_live()
        pos=f'{self.position[0]:.5f}, {self.position[1]:.5f}' if self.position else 'nieustawiona'
        self.footer.setText(f"{'DEMO • osobna baza w pamięci' if self.store is self.demo_store else 'SQLite • '+str(self.data_dir/'skylog1090.sqlite')}   |   Odbiornik: {pos}")

    def refresh_live(self):
        live=self.tracker.live(); terms=self.search.text().upper().split()
        filtered=[a for a in live if all(t in (a['icao']+' '+a.get('callsign','')).upper() for t in terms)
            and (not self.position_only.isChecked() or (a.get('latitude') is not None and time.time()-a.get('position_time',0)<=120))
            and (self.min_alt.value()==-2000 or (a.get('altitude') is not None and a['altitude']>=self.min_alt.value()))]
        self.metrics[0].setText(str(len(live))); self.metrics[1].setText(f'{self.tracker.total:,}'.replace(',',' '))
        distances=[distance_nm(*self.position,a['latitude'],a['longitude']) for a in live if self.position and a.get('latitude') is not None and time.time()-a.get('position_time',0)<=120]
        self.metrics[3].setText(f'{max(distances):.1f} NM' if distances else '—')
        self.radar.aircraft=filtered; self.radar.update()
        self.live_table.blockSignals(True); self.live_table.setSortingEnabled(False); self.live_table.setRowCount(len(filtered))
        for r,a in enumerate(sorted(filtered,key=lambda a:a['icao'])):
            distance=distance_nm(*self.position,a['latitude'],a['longitude']) if self.position and a.get('latitude') is not None and time.time()-a.get('position_time',0)<=120 else None
            values=[(a['icao']+'  '+a.get('callsign',''),None),(str(a.get('altitude','—')),a.get('altitude')),
                (f"{a['speed']:.0f}" if a.get('speed') is not None else '—',a.get('speed')),
                (f"{a['heading']:.0f}" if a.get('heading') is not None else '—',a.get('heading')),
                (f'{distance:.1f}' if distance is not None else '—',distance),
                (str(int(time.time()-a['last'])),time.time()-a['last']),(str(a['frames']),a['frames'])]
            for c,(text,value) in enumerate(values):
                item=NumberItem(text,value) if c else QTableWidgetItem(text); item.setData(Qt.ItemDataRole.UserRole,a['icao']); self.live_table.setItem(r,c,item)
            if a['icao']==self.selected: self.live_table.selectRow(r)
        self.live_table.setSortingEnabled(True); self.live_table.blockSignals(False); self.refresh_detail()

    def table_selected(self):
        items=self.live_table.selectedItems()
        if items: self.select(items[0].data(Qt.ItemDataRole.UserRole))

    def select(self,icao):
        self.selected=icao; self.radar.selected_icao=icao; self.refresh_live()

    def refresh_detail(self):
        a=self.tracker.aircraft.get(self.selected)
        if not a:
            self.detail_title.setText('—'); self.detail.setText('Wybierz samolot na radarze\nlub w tabeli.'); return
        self.detail_title.setText(a.get('callsign') or a['icao'])
        position=f"{a['latitude']:.5f}, {a['longitude']:.5f}" if a.get('latitude') is not None else '—'
        speed=f"{a['speed']:.1f} kt" if a.get('speed') is not None else '—'
        heading=f"{a['heading']:.1f}°" if a.get('heading') is not None else '—'
        self.detail.setText(f"ICAO  {a['icao']}\n\nWysokość  {a.get('altitude','—')} ft\nPrędkość  {speed}\nKurs  {heading}\nPionowo  {a.get('vertical_rate','—')} ft/min\n\nPozycja\n{position}\n\nOstatnio  {utc(a['last'])[11:19]} UTC\nRamki  {a['frames']}\n\nRejestracja / operator / typ\nNieznane — brak lokalnej bazy tożsamości.")

    def history_selected(self):
        self.history_search.setText(self.selected); self.navigate(1)

    def filtered_rows(self):
        since=until=None
        if not self.all_dates.isChecked():
            since=datetime(self.from_date.date().year(),self.from_date.date().month(),self.from_date.date().day(),tzinfo=timezone.utc).timestamp()
            until=datetime(self.to_date.date().year(),self.to_date.date().month(),self.to_date.date().day(),tzinfo=timezone.utc).timestamp()+86400
            if until<=since: raise ValueError('Data końcowa musi być co najmniej datą początkową.')
        return self.store.rows(self.history_search.text(),since,until)

    def refresh_history(self):
        try: rows=self.filtered_rows()
        except Exception as exc: QMessageBox.warning(self,'Historia',str(exc)); return
        self.history_rows=rows; self.history_table.setRowCount(len(rows))
        for i,r in enumerate(rows):
            values=[r['icao'],r.get('callsign','—'),utc(r['first'])[:19],utc(r['last'])[:19],r['frames'],r.get('max_altitude','—'),round(r['max_speed'],1) if r.get('max_speed') is not None else '—',r['source']]
            for c,value in enumerate(values): self.history_table.setItem(i,c,QTableWidgetItem(str(value)))
        self.history_count.setText(f'{len(rows)} wizyt • {len({r["icao"] for r in rows})} ICAO • {sum(r["frames"] for r in rows)} ramek • dwuklik: szczegóły')

    def history_detail(self,item):
        r=self.history_rows[item.row()]
        # Show bounded persisted historical track in an independent interactive radar.
        from PySide6.QtWidgets import QDialog
        dialog=QDialog(self); dialog.setWindowTitle('Historia • '+r['icao']); dialog.resize(950,650)
        l=QVBoxLayout(dialog); l.addWidget(label(f"{r['icao']}  {r.get('callsign','')}   |   {utc(r['first'])} → {utc(r['last'])}   |   {r['source']}"))
        radar=Radar(); a=dict(r); a['position_time']=time.time()
        if a.get('latitude') is not None: radar.center=(a['latitude'],a['longitude'])
        radar.aircraft=[a]; radar.selected_icao=r['icao']; l.addWidget(radar,1)
        l.addWidget(label('Zapisana trasa historyczna. Czas na mapie nie oznacza odbioru LIVE.','muted')); dialog.exec()

    def capture_report(self):
        try: self.report_rows=self.filtered_rows()
        except Exception as exc: QMessageBox.warning(self,'Raport',str(exc)); return
        self.report_at=time.time(); rows=self.report_rows
        vals=[len({r['icao'] for r in rows}),len(rows),sum(r['frames'] for r in rows),max((r['max_range'] for r in rows if r.get('max_range') is not None),default=None)]
        for m,v in zip(self.report_metrics,vals): m.setText('—' if v is None else f'{v:,.1f}' if isinstance(v,float) else str(v))
        self.report_caption.setText(f"Zapisany obraz danych: {utc(self.report_at)} • filtr: {self.history_search.text() or 'wszystkie ICAO'} • źródło: {'DEMO' if self.store is self.demo_store else 'LIVE / REPLAY'}. Eksporty używają dokładnie tych danych.")
        seen={}
        for r in rows: seen[r['icao']]=min(seen.get(r['icao'],r['first']),r['first'])
        self.chart.values=[0]*24
        for t in seen.values(): self.chart.values[datetime.fromtimestamp(t,timezone.utc).hour]+=1
        self.chart.update(); ranked=sorted(rows,key=lambda r:r['frames'],reverse=True)[:100]
        self.rank.setRowCount(len(ranked))
        for i,r in enumerate(ranked):
            values=[r['icao'],r.get('callsign','—'),r['frames'],r.get('max_altitude','—'),r.get('max_speed','—'),r.get('max_range','—')]
            for c,v in enumerate(values): self.rank.setItem(i,c,QTableWidgetItem(f'{v:.1f}' if isinstance(v,float) else str(v)))
        self.navigate(2)

    def export_history(self):
        try: rows=self.filtered_rows()
        except Exception as exc: QMessageBox.warning(self,'Eksport',str(exc)); return
        self.export(rows)

    def export_snapshot(self,fmt):
        if self.report_at is None:
            self.capture_report()
            if self.report_at is None: return
        self.export(self.report_rows,fmt)

    def export(self,rows,fmt='CSV'):
        path,_=QFileDialog.getSaveFileName(self,'Eksport danych',str(self.data_dir/f'SkyLog-{datetime.now():%Y%m%d-%H%M%S}.{fmt.lower()}'),f'{fmt} (*.{fmt.lower()})')
        if not path:return
        if not Path(path).suffix: path+='.'+fmt.lower()
        try:
            export_report(path,rows); QMessageBox.information(self,'Eksport zakończony',f'Zapisano {len(rows)} wizyt:\n{path}')
        except Exception as exc: QMessageBox.warning(self,'Błąd eksportu',str(exc))

    def start_demo(self):
        if self.mode!='IDLE' or self.worker:return
        self.begin('DEMO'); self.demo_step=0
        self.status.setText('● DEMO • SYMULACJA • brak odbioru radiowego • dane poza rzeczywistą historią')
        # Fixed demo reference, visibly labelled; never stored as receiver location.
        self.radar.center=(52.0,19.5); self.radar.offset.setX(0); self.radar.offset.setY(0)
        self.demo_tick(); self.demo_timer.start(1000); self.navigate(0)

    def demo_tick(self):
        self.demo_step+=1; now=time.time(); frames=[]
        for i,(icao,call) in enumerate([('40621D','DEMO101'),('4840D6','DEMO202'),('ABC123','DEMO303'),('C0FFEE','DEMO404'),('123ABC','DEMO505'),('A1B2C3','DEMO606')]):
            angle=i*1.05+self.demo_step*.004
            frames.append(Frame(icao,now,11,call,24000+i*2000,52+math.sin(angle)*(.5+i*.12),19.5+math.cos(angle)*(1+i*.15),340+i*13,(math.degrees(angle)+180)%360,0,False))
        self.accept(frames); self.refresh_live()
        if not self.selected:self.select('40621D')

    def open_replay(self):
        if self.mode!='IDLE' or self.worker:return
        path,_=QFileDialog.getOpenFileName(self,'Odtwórz ramki AVR','','Ramki AVR (*.avr *.txt);;Wszystkie pliki (*)')
        if not path:return
        try:
            if Path(path).stat().st_size>50_000_000: raise ValueError('Limit pliku AVR: 50 MB.')
            self.replay_lines=iter(Path(path).read_text(encoding='ascii',errors='ignore').splitlines())
            self.replay_decoder=Decoder(); self.begin('REPLAY'); self.replay_timer.start(20)
            self.status.setText('● REPLAY • odtwarzanie pliku • 50 ramek/s • czas odtworzenia w historii')
        except Exception as exc: QMessageBox.warning(self,'Odtwarzanie',str(exc))

    def replay_tick(self):
        line=next(self.replay_lines,None)
        if line is None:
            self.stop(); self.status.setText(f'REPLAY zakończony • CRC OK: {self.tracker.total} • odrzucone CRC: {self.replay_decoder.rejected}'); return
        try:
            text=line.strip().strip('*;')
            if text.startswith('@'): text=text[13:]
            f=self.replay_decoder.decode(bytes.fromhex(text))
            if f:self.accept([f])
        except ValueError:pass

    def closeEvent(self,event):
        if self.worker:
            self.worker.stop(); self.status.setText('Zatrzymywanie USB przed zamknięciem…')
            # Keep the window/event loop alive until libusb confirms cancellation.
            event.ignore(); QTimer.singleShot(250,self.close); return
        if self.location_worker and self.location_worker.isRunning():
            event.ignore(); QTimer.singleShot(250,self.close); return
        self.demo_timer.stop(); self.replay_timer.stop(); self.timer.stop()
        self.real_store.close(); self.demo_store.close(); self.settings.sync(); event.accept()


def main():
    import argparse
    parser=argparse.ArgumentParser(); parser.add_argument('--demo',action='store_true'); parser.add_argument('--data-dir')
    parser.add_argument('--smoke-test',action='store_true'); parser.add_argument('--screenshot')
    parser.add_argument('--verify-release',metavar='DIRECTORY'); args=parser.parse_args()
    app=QApplication(sys.argv); app.setStyle('Fusion'); app.setStyleSheet(STYLE)
    app.setApplicationName('SkyLog 1090'); app.setOrganizationName('SkyLog1090')
    from PySide6.QtCore import QLockFile
    data_dir=Path(args.data_dir or Path(os.environ.get('LOCALAPPDATA',str(Path.home())))/'SkyLog1090')
    data_dir.mkdir(parents=True,exist_ok=True)
    lock=QLockFile(str(data_dir/'application.lock')); lock.setStaleLockTime(30000)
    if not lock.tryLock(0):
        QMessageBox.information(None,'SkyLog 1090','SkyLog 1090 jest już uruchomiony dla tego folderu danych.'); return
    try:w=Window(data_dir)
    except Exception as exc:
        QMessageBox.critical(None,'SkyLog 1090',f'Nie można otworzyć lokalnej bazy danych:\n{exc}'); return
    w.show()
    if args.demo or args.verify_release:w.start_demo()
    if args.verify_release:
        def verify():
            destination=Path(args.verify_release).resolve();destination.mkdir(parents=True,exist_ok=True)
            result={}
            try:
                devices=RtlLibrary().devices();result['driver_loaded']=True;result['device_count']=len(devices)
                w.capture_report();result['demo_aircraft']=len(w.report_rows)
                for fmt in ('csv','xlsx','pdf'):export_report(destination/f'demo-report.{fmt}',w.report_rows)
                result['exports']=['csv','xlsx','pdf'];result['real_history_rows']=len(w.real_store.rows())
                w.navigate(4);app.processEvents();w.grab().save(str(destination/'antenna.png'))
                w.navigate(0);app.processEvents();w.grab().save(str(destination/'dashboard-demo.png'))
                result['ok']=result['demo_aircraft']==6 and result['real_history_rows']==0
            except Exception as exc:result.update(ok=False,error=str(exc))
            (destination/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf8')
            w.close()
            if not result['ok']:app.exit(1)
        QTimer.singleShot(2500,verify)
    if args.smoke_test:
        def finish():
            if args.screenshot:w.grab().save(args.screenshot)
            w.close()
        QTimer.singleShot(2500,finish)
    sys.exit(app.exec())


if __name__=='__main__':main()
