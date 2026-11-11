import time
import pytest
from PySide6.QtWidgets import QApplication
from PySide6.QtCore import Qt,QPoint
from PySide6.QtTest import QTest
from skylog.app import Window,STYLE


@pytest.fixture(scope='module')
def app():
    a=QApplication.instance() or QApplication([]);a.setStyleSheet(STYLE);yield a


def test_dashboard_selection_filter_report_and_dipole(app,tmp_path):
    w=Window(tmp_path);w.show();app.processEvents();w.start_demo();app.processEvents()
    assert w.live_table.rowCount()==6;assert w.start_button.isEnabled() is False
    w.select('4840D6');assert 'DEMO202' in w.detail_title.text()
    w.search.setText('DEMO303');assert w.live_table.rowCount()==1;assert len(w.radar.aircraft)==1
    w.search.clear();w.history_search.setText('DEMO');w.refresh_history();assert w.history_table.rowCount()==6
    w.capture_report();assert len(w.report_rows)==6 and w.report_metrics[0].text()=='6'
    total=sum(r['frames'] for r in w.report_rows);w.demo_tick();assert sum(r['frames'] for r in w.report_rows)==total
    w.navigate(4);assert w.pages.currentIndex()==4
    w.stop();w.close();app.processEvents()


def test_radio_missing_device_finishes_without_fake_live(app,tmp_path,monkeypatch):
    from skylog import radio
    class Missing:
        def devices(self):return []
    monkeypatch.setattr(radio,'RtlLibrary',Missing)
    w=Window(tmp_path);w.start_radio()
    deadline=time.monotonic()+5
    while w.worker and time.monotonic()<deadline:
        app.processEvents();QTest.qWait(10)
    assert w.worker is None;assert 'BŁĄD' in w.status.text();assert w.tracker.total==0
    assert w.real_store.rows()==[];w.close()


def test_manual_location_zero_and_clearing(app,tmp_path):
    w=Window(tmp_path); original=w.settings.value('position','')
    w.latitude.setValue(0);w.longitude.setValue(0);w.position_known.setChecked(True);w.save_position()
    assert w.position==(0,0) and w.radar.receiver==(0,0)
    w.position_known.setChecked(False);w.save_position();assert w.position is None
    w.settings.setValue('position',original);w.close()


def test_rf_diagnostics_distinguishes_iq_from_confirmed_adsb(app,tmp_path):
    import threading
    from types import SimpleNamespace
    w=Window(tmp_path);w.mode='LIVE';w.worker=SimpleNamespace(stopping=threading.Event())
    m=dict(samples=2000000,accepted=0,rejected=17,drops=0,queue=0,preambles=100,candidates_adsb=20,power_dbfs=-40.,peak_dbfs=-28.,clipped_percent=0.)
    w.show_rf_metrics(m)
    assert 'IQ AKTYWNE' in w.status.text() and 'LIVE ADS-B' not in w.status.text()
    assert w.rf_cards[0].text()=='2.0 mln' and w.rf_cards[5].text()=='17'
    m['accepted']=1;w.show_rf_metrics(m);assert 'LIVE ADS-B' in w.status.text()
    w.worker=None;w.mode='IDLE';w.close()
