"""Local SQLite visits, sessions and immutable report exports."""
import csv
import json
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

COLUMNS = ['icao','callsign','first_utc','last_utc','frames','altitude_ft','max_altitude_ft',
           'speed_kt','max_speed_kt','heading_deg','latitude','longitude','max_range_nm','source','session']


def utc(t):
    return datetime.fromtimestamp(t, timezone.utc).isoformat(timespec='seconds')


class Store:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, start REAL,
            last REAL, end REAL, source TEXT, lat REAL, lon REAL);
          CREATE TABLE IF NOT EXISTS visits(id TEXT PRIMARY KEY, session TEXT REFERENCES sessions(id),
            icao TEXT, first REAL, last REAL, frames INTEGER, data TEXT);
          CREATE INDEX IF NOT EXISTS visits_time ON visits(last, first);
          CREATE INDEX IF NOT EXISTS visits_icao ON visits(icao);
        ''')
        # Crash recovery: last committed heartbeat is the end of prior sessions.
        self.db.execute('UPDATE sessions SET end=last WHERE end IS NULL')
        self.db.commit()
        self.session = None
        self.current = {}
        self.pending = {}
        self.source = ''

    def start(self, source, position=None, now=None):
        self.stop(now)
        now = time.time() if now is None else now
        self.session = uuid4().hex
        self.source = source
        self.current.clear()
        self.db.execute('INSERT INTO sessions VALUES (?,?,?,?,?,?,?)',
                        (self.session,now,now,None,source,*(position or (None,None))))
        self.db.commit()

    def accept(self, f, aircraft, distance=None):
        if not self.session:
            return
        old = self.current.get(f.icao)
        if old is None or f.timestamp-old['last'] > 300:
            old = dict(id=uuid4().hex, icao=f.icao, first=f.timestamp, last=f.timestamp,
                       frames=0, track=[], source=self.source, session=self.session)
            self.current[f.icao] = old
        old['frames'] += 1
        old['first'] = min(old['first'], f.timestamp)
        if f.timestamp >= old['last']:
            for key in ('callsign','altitude','speed','heading','vertical_rate','latitude','longitude'):
                value = getattr(f,key)
                if value is not None:
                    old[key] = value
            if f.latitude is not None and (not old['track'] or f.timestamp-old['track'][-1][0] >= 10):
                old['track'].append([f.timestamp, f.latitude, f.longitude, f.altitude])
                if len(old['track']) > 512:
                    points=old['track']
                    old['track'] = points[::2] + ([points[-1]] if len(points)%2==0 else [])
        old['last'] = max(old['last'], f.timestamp)
        for key, value in [('max_altitude', f.altitude), ('max_speed', f.speed), ('max_range', distance)]:
            if value is not None:
                old[key] = max(old.get(key, value), value)
        self.pending[old['id']] = old

    def flush(self, now=None):
        now = time.time() if now is None else now
        with self.db:
            for row in self.pending.values():
                self.db.execute('INSERT OR REPLACE INTO visits VALUES (?,?,?,?,?,?,?)',
                    (row['id'],self.session,row['icao'],row['first'],row['last'],row['frames'],json.dumps(row)))
            if self.session:
                self.db.execute('UPDATE sessions SET last=? WHERE id=?',(now,self.session))
        self.pending.clear()
        self.current = {k:v for k,v in self.current.items() if now-v['last'] <= 300}

    def stop(self, now=None):
        if self.session:
            self.flush(now)
            with self.db:
                self.db.execute('UPDATE sessions SET end=last WHERE id=?',(self.session,))
        self.session = None
        self.current.clear()

    def rows(self, query='', since=None, until=None, source=None):
        self.flush()
        clauses, args = [], []
        if since is not None:
            clauses.append('last>=?'); args.append(since)
        if until is not None:
            clauses.append('first<?'); args.append(until)
        sql = 'SELECT data FROM visits'+(' WHERE '+' AND '.join(clauses) if clauses else '')+' ORDER BY last DESC'
        terms = query.upper().split()
        rows = [json.loads(r[0]) for r in self.db.execute(sql,args)]
        return [r for r in rows if (source is None or r['source'] == source) and
                all(t in (r['icao']+' '+r.get('callsign','')).upper() for t in terms)]

    def close(self):
        self.stop()
        self.db.close()


def report_values(row):
    return [row['icao'],row.get('callsign',''),utc(row['first']),utc(row['last']),row['frames'],
        row.get('altitude'),row.get('max_altitude'),row.get('speed'),row.get('max_speed'),
        row.get('heading'),row.get('latitude'),row.get('longitude'),row.get('max_range'),row['source'],row['session']]


def export_report(path, rows):
    """Every format uses the same captured filtered rows; unknowns stay blank."""
    path = Path(path)
    if path.suffix.lower() == '.csv':
        with path.open('w', newline='', encoding='utf-8-sig') as stream:
            w = csv.writer(stream); w.writerow(COLUMNS)
            for row in rows:
                values = report_values(row)
                # Spreadsheet-safe text. A callsign can contain attacker-controlled input.
                values = ["'"+v if isinstance(v,str) and v.startswith(('=','+','-','@','\t','\r')) else v for v in values]
                w.writerow(values)
    elif path.suffix.lower() == '.xlsx':
        from openpyxl import Workbook
        from openpyxl.styles import Font, PatternFill
        from openpyxl.utils import get_column_letter
        wb = Workbook(); ws = wb.active; ws.title = 'AIRCRAFT'
        ws.append(COLUMNS)
        for row in rows:
            ws.append(report_values(row))
            for cell in ws[ws.max_row]:
                if isinstance(cell.value,str):
                    cell.data_type = 's'
        ws.freeze_panes = 'A2'; ws.auto_filter.ref = ws.dimensions
        for cell in ws[1]:
            cell.fill=PatternFill('solid',fgColor='123142'); cell.font=Font(color='FFFFFF',bold=True)
        for i in range(1,len(COLUMNS)+1):
            ws.column_dimensions[get_column_letter(i)].width = 24 if i in (3,4,15) else 18
        summary=wb.create_sheet('SUMMARY'); summary.append(['SkyLog 1090','Windows MVP'])
        summary.append(['Unique aircraft',len({r['icao'] for r in rows})]); summary.append(['Visits',len(rows)])
        summary.append(['Frames',sum(r['frames'] for r in rows)]); summary.append(['Generated UTC',utc(time.time())])
        tracks=wb.create_sheet('TRACKS'); tracks.append(['icao','utc','latitude','longitude','altitude_ft'])
        for r in rows:
            for p in r.get('track',[]):
                tracks.append([r['icao'],utc(p[0]),*p[1:]])
        wb.save(path)
    elif path.suffix.lower() == '.pdf':
        from reportlab.pdfgen.canvas import Canvas
        from reportlab.lib.pagesizes import landscape, A4
        c=Canvas(str(path),pagesize=landscape(A4))
        def header():
            c.setFont('Helvetica-Bold',20); c.drawString(40,550,'SkyLog 1090 | Reception report')
            c.setFont('Helvetica',10); c.drawString(40,528,f"UTC {utc(time.time())}  |  Aircraft {len({r['icao'] for r in rows})}  |  Visits {len(rows)}  |  Frames {sum(r['frames'] for r in rows)}")
            c.drawString(40,505,'ICAO     CALLSIGN        LAST SEEN (UTC)                   FRAMES     MAX ALT ft     MAX SPEED kt    SOURCE')
        header(); y=480
        for r in rows:
            c.setFont('Courier',9)
            speed=str(round(r['max_speed'],1)) if r.get('max_speed') is not None else '-'
            text=f"{r['icao']:8} {r.get('callsign','-')[:10]:12} {utc(r['last'])[:19]:23} {r['frames']:8} {str(r.get('max_altitude','-')):>12} {speed:>12}  {r['source']}"
            c.drawString(40,y,text); y-=18
            if y<45:
                c.showPage(); header(); y=480
        c.save()
    else:
        raise ValueError('Choose .csv, .xlsx or .pdf')
