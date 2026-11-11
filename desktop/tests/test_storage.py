import csv
import sqlite3
from skylog.core import Frame,Tracker
from skylog.storage import Store,export_report


def add(s,f):
    t=Tracker(); s.accept(f,t.accept(f))


def test_restart_filters_and_5_minute_visits(tmp_path):
    path=tmp_path/'history.sqlite'; s=Store(path); s.start('LIVE',now=1)
    add(s,Frame('ABC123',100,1,'LOT123')); add(s,Frame('ABC123',101,19,speed=410))
    add(s,Frame('ABC123',402,11,altitude=12000)); s.stop(500); s.close()
    s=Store(path); rows=s.rows('abc123'); assert len(rows)==2 and sum(r['frames'] for r in rows)==3
    assert len(s.rows('LOT123'))==1; assert not s.rows('missing'); assert len(s.rows(since=400,until=450))==1
    assert s.db.execute('SELECT COUNT(*) FROM sessions WHERE end IS NULL').fetchone()[0]==0
    s.close()


def test_crash_recovery_and_empty_session(tmp_path):
    path=tmp_path/'test.sqlite'; s=Store(path); s.start('LIVE',now=1); s.flush(5)
    s.db.close(); s=Store(path)
    assert s.db.execute('SELECT start,end FROM sessions').fetchone()==(1,5)
    assert s.rows()==[]; s.close()


def test_partial_and_out_of_order_do_not_overwrite(tmp_path):
    s=Store(tmp_path/'db'); s.start('LIVE',now=1)
    add(s,Frame('ABC123',100,1,'NEW',20000,0.,0.));add(s,Frame('ABC123',99,1,'OLD',10000,1.,1.))
    add(s,Frame('ABC123',101,19,speed=200))
    row=s.rows()[0];assert row['callsign']=='NEW' and row['latitude']==0 and row['altitude']==20000
    assert row['max_altitude']==20000 and row['frames']==3;s.close()


def test_csv_xlsx_pdf_snapshot_and_formula_safety(tmp_path):
    from openpyxl import load_workbook
    s=Store(tmp_path/'db');s.start('REPLAY',now=1);add(s,Frame('ABC123',100,1,'=1+1',latitude=0,longitude=0))
    rows=s.rows()
    for fmt in ('csv','xlsx','pdf'):export_report(tmp_path/f'report.{fmt}',rows)
    with (tmp_path/'report.csv').open(encoding='utf-8-sig') as stream:
        data=list(csv.reader(stream));assert data[1][1]=="'=1+1" and data[1][4]=='1'
    wb=load_workbook(tmp_path/'report.xlsx');assert wb['AIRCRAFT']['B2'].value=='=1+1'
    assert wb['AIRCRAFT']['B2'].data_type=='s';assert wb['SUMMARY']['B4'].value==1
    assert wb['TRACKS'].max_row==2;assert (tmp_path/'report.pdf').read_bytes().startswith(b'%PDF')
    assert rows[0]['callsign']=='=1+1';s.close()


def test_demo_database_separate_from_real(tmp_path):
    real=Store(tmp_path/'real');demo=Store(':memory:');demo.start('DEMO',now=1)
    add(demo,Frame('ABC123',10));assert len(demo.rows())==1 and real.rows()==[]
    demo.close();real.close()
