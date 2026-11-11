"""Offline geographic radar. Wheel zoom, drag pan, click aircraft."""
import json
import math
import time
from pathlib import Path
from PySide6.QtCore import Qt, QPointF, Signal, QRectF
from PySide6.QtGui import QColor, QPainter, QPen, QPainterPath, QFont
from PySide6.QtWidgets import QWidget


class Radar(QWidget):
    selected=Signal(str)
    def __init__(self):
        super().__init__()
        self.setMinimumSize(440,320)
        self.setMouseTracking(True)
        self.center=None; self.receiver=None
        self.range=160.; self.offset=QPointF(); self.drag=None; self.moved=False
        self.aircraft=[]; self.selected_icao=''; self.hits=[]
        world=Path(__file__).resolve().parent.parent/'assets'/'world.geojson'
        self.polygons=[]
        if world.exists():
            for feature in json.loads(world.read_text(encoding='utf8'))['features']:
                g=feature['geometry']
                polys=g['coordinates'] if g['type']=='MultiPolygon' else [g['coordinates']]
                for poly in polys:
                    if len(poly[0])>2: self.polygons.append(poly[0])

    def reset(self):
        self.offset=QPointF(); self.range=160
        self.center=self.receiver or next(((a['latitude'],a['longitude']) for a in self.aircraft if a.get('latitude') is not None),None)
        self.update()

    def projection(self,lat,lon):
        a,b=self.center or (52.,19.)
        scale=min(self.width(),self.height())*.44/self.range
        dx=((lon-b+180)%360-180)*60*math.cos(math.radians(a))
        return QPointF(self.width()/2+dx*scale+self.offset.x(),self.height()/2-(lat-a)*60*scale+self.offset.y())

    def paintEvent(self,event):
        p=QPainter(self); p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.fillRect(self.rect(),QColor('#0b1522'))
        if self.center is None:
            self.center=self.receiver or next(((a['latitude'],a['longitude']) for a in self.aircraft if a.get('latitude') is not None),None)
        # Grid and land are geographically referenced to the current center.
        p.setPen(QPen(QColor('#152536'),1))
        for x in range(0,self.width(),48): p.drawLine(x,0,x,self.height())
        for y in range(0,self.height(),48): p.drawLine(0,y,self.width(),y)
        if self.center:
            p.setPen(QPen(QColor('#2a4354'),1)); p.setBrush(QColor('#112330'))
            for poly in self.polygons:
                points=[self.projection(lat,lon) for lon,lat,*_ in poly]
                xs=[q.x() for q in points]; ys=[q.y() for q in points]
                if max(xs)<0 or min(xs)>self.width() or max(ys)<0 or min(ys)>self.height(): continue
                path=QPainterPath(points[0])
                for q in points[1:]: path.lineTo(q)
                path.closeSubpath(); p.drawPath(path)
            # Modest city reference labels, not aircraft metadata.
            p.setFont(QFont('Segoe UI',9)); p.setPen(QColor('#547183'))
            for name,lat,lon in [('Warszawa',52.23,21.01),('Kraków',50.06,19.94),('Gdańsk',54.35,18.65),('Poznań',52.41,16.93),('Wrocław',51.11,17.04),('Berlin',52.52,13.41),('Praha',50.08,14.44),('Vilnius',54.69,25.28),('Bratislava',48.15,17.11),('Łódź',51.76,19.46),('Lublin',51.25,22.57)]:
                q=self.projection(lat,lon); p.drawEllipse(q,2,2); p.drawText(q+QPointF(6,4),name)
        center=self.projection(*self.receiver) if self.receiver else QPointF(self.width()/2,self.height()/2)+self.offset
        scale=min(self.width(),self.height())*.44/self.range
        p.setBrush(Qt.BrushStyle.NoBrush)
        for nm in (self.range/4,self.range/2,self.range*.75,self.range):
            radius=nm*scale
            p.setPen(QPen(QColor('#284352'),1,Qt.PenStyle.DashLine)); p.drawEllipse(center,radius,radius)
            p.setPen(QColor('#628392')); p.drawText(center+QPointF(6,-radius+14),f'{nm:g} NM')
        if self.receiver:
            p.setPen(QPen(QColor('#4fe0c3'),2)); p.drawLine(center+QPointF(-8,0),center+QPointF(8,0))
            p.drawLine(center+QPointF(0,-8),center+QPointF(0,8)); p.drawEllipse(center,4,4)
        self.hits=[]
        now=time.time()
        for a in self.aircraft:
            if a.get('latitude') is None: continue
            age=now-a.get('position_time',0)
            if age>120: continue
            chosen=a['icao']==self.selected_icao
            color=QColor('#ffcb77') if chosen else QColor('#50dccc')
            if age>30: color=QColor('#738a96')
            p.setPen(QPen(color,2 if chosen else 1)); p.setBrush(Qt.BrushStyle.NoBrush)
            track=a.get('track',[])
            if len(track)>1:
                path=QPainterPath(self.projection(track[0][1],track[0][2]))
                for point in track[1:]: path.lineTo(self.projection(point[1],point[2]))
                p.drawPath(path)
            q=self.projection(a['latitude'],a['longitude']); self.hits.append((q,a['icao']))
            if chosen:
                p.setPen(QPen(color,1)); p.drawEllipse(q,17,17)
            p.save(); p.translate(q); p.rotate(a.get('heading') or 0)
            shape=QPainterPath(QPointF(0,-11))
            for x,y in [(3,-2),(11,4),(11,7),(2,3),(2,8),(5,11),(0,9),(-5,11),(-2,8),(-2,3),(-11,7),(-11,4),(-3,-2)]: shape.lineTo(x,y)
            shape.closeSubpath(); p.setPen(Qt.PenStyle.NoPen); p.setBrush(color); p.drawPath(shape); p.restore()
            p.setPen(color); p.setFont(QFont('Segoe UI',9,QFont.Weight.DemiBold))
            p.drawText(q+QPointF(16,-3),a.get('callsign') or a['icao'])
            p.setFont(QFont('Segoe UI',8)); p.setPen(QColor('#90a7b4'))
            p.drawText(q+QPointF(16,12),f"{a.get('altitude','—')} ft · {a.get('speed',0):.0f} kt" if a.get('speed') is not None else f"{a.get('altitude','—')} ft")
        p.setFont(QFont('Segoe UI',9)); p.setPen(QColor('#8fa5b6'))
        p.drawText(18,25,'N ↑    RADAR GEOGRAFICZNY / OFFLINE')
        p.drawText(18,self.height()-16,'Kółko: zoom   •   Przeciągnij: przesuń   •   Kliknij: wybierz')
        if self.center is None:
            p.setPen(QColor('#a8bac8')); p.drawText(self.rect(),Qt.AlignmentFlag.AlignCenter,'Ustaw lokalizację odbiornika\nlub poczekaj na pozycję samolotu.\nBrak danych pozycyjnych.')
        elif not self.aircraft:
            p.setPen(QColor('#8fa5b6')); p.drawText(self.rect(),Qt.AlignmentFlag.AlignCenter,'Oczekiwanie na dane ADS-B\nPozycja wymaga aktualnej pary ramek CPR.')

    def wheelEvent(self,e):
        self.range=max(10,min(1000,self.range*(.8 if e.angleDelta().y()>0 else 1.25)))
        self.update()

    def mousePressEvent(self,e):
        self.drag=e.position(); self.moved=False; self.setCursor(Qt.CursorShape.ClosedHandCursor)

    def mouseMoveEvent(self,e):
        if self.drag is not None:
            delta=e.position()-self.drag
            if delta.manhattanLength()>2: self.moved=True
            self.offset+=delta; self.drag=e.position(); self.update()
        else:
            match=next((icao for q,icao in self.hits if (q-e.position()).manhattanLength()<24),None)
            self.setCursor(Qt.CursorShape.PointingHandCursor if match else Qt.CursorShape.OpenHandCursor)

    def mouseReleaseEvent(self,e):
        if not self.moved:
            match=min(self.hits,key=lambda hit:(hit[0]-e.position()).manhattanLength(),default=None)
            if match and (match[0]-e.position()).manhattanLength()<30: self.selected.emit(match[1])
        self.drag=None; self.setCursor(Qt.CursorShape.OpenHandCursor)


class Dipole(QWidget):
    def __init__(self):
        super().__init__(); self.setMinimumHeight(240)
    def paintEvent(self,e):
        p=QPainter(self); p.setRenderHint(QPainter.RenderHint.Antialiasing)
        x=self.width()/2; y=self.height()/2
        p.fillRect(self.rect(),QColor('#0b1522'))
        p.setPen(QPen(QColor('#4fe0c3'),7,Qt.PenStyle.SolidLine,Qt.PenCapStyle.RoundCap))
        p.drawLine(QPointF(x,y-90),QPointF(x,y-8)); p.drawLine(QPointF(x,y+8),QPointF(x,y+90))
        p.setPen(QPen(QColor('#71899c'),3)); p.drawLine(QPointF(x+8,y),QPointF(x+100,y))
        p.setPen(QColor('#dbe8f2')); p.setFont(QFont('Segoe UI',11))
        p.drawText(QPointF(x-140,y-45),'≈ 69 mm'); p.drawText(QPointF(x-140,y+50),'≈ 69 mm')
        p.drawText(QPointF(x+18,y-12),'przewód do SDR')
        p.setPen(QColor('#4fe0c3')); p.drawText(18,24,'1090 MHz • DIPO L PIONOWY'.replace('DIPO L','DIPOL'))
