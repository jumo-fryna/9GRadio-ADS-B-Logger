"""Development visual review and exported demo snapshots, no real RF data."""
import argparse
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from PySide6.QtWidgets import QApplication
from PySide6.QtCore import QTimer
from skylog.app import Window,STYLE
from skylog.storage import export_report

parser=argparse.ArgumentParser();parser.add_argument('destination');args=parser.parse_args()
dest=Path(args.destination).resolve();dest.mkdir(parents=True,exist_ok=True)
app=QApplication([]);app.setStyle('Fusion');app.setStyleSheet(STYLE)
w=Window(dest/'review-data');w.resize(1560,960);w.show();w.start_demo()

def capture():
    for _ in range(60):w.demo_tick()
    w.refresh_live();app.processEvents();w.grab().save(str(dest/'SkyLog1090-dashboard-demo.png'))
    w.navigate(4);app.processEvents();w.grab().save(str(dest/'SkyLog1090-antenna.png'))
    w.capture_report();app.processEvents();w.grab().save(str(dest/'SkyLog1090-report-demo.png'))
    for fmt in ('csv','xlsx','pdf'):export_report(dest/f'SkyLog1090-demo-report.{fmt}',w.report_rows)
    w.resize(1120,760);w.navigate(4);app.processEvents();w.grab().save(str(dest/'antenna-small.png'))
    w.stop();w.close()

QTimer.singleShot(300,capture);app.exec()
