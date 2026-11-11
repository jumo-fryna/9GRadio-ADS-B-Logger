import json
from pathlib import Path
import numpy as np
import pytest
from skylog.core import Decoder,IqDecoder,Frame,Tracker,crc24,distance_nm,valid_position

EVEN='8D40621D58C382D690C8AC2863A7'
ODD='8D40621D58C386435CC412692AD6'


def encoded(me,icao='40621D',df=0x8D):
    head=bytes([df])+bytes.fromhex(icao)+me.to_bytes(7,'big')
    return head+crc24(head).to_bytes(3,'big')


def field(me,start,length,value):
    return me | (value << (56-start-length+1))


def iq_frame(raw,offset=0,tail=300):
    iq=np.full((offset+240+tail,2),128,dtype=np.uint8)
    for p in (0,2,7,9):iq[offset+p,0]=255
    for i,bit in enumerate(np.unpackbits(np.frombuffer(raw,dtype=np.uint8))):iq[offset+16+i*2+(0 if bit else 1),0]=255
    return iq.tobytes()


def test_shared_android_protocol_vectors():
    fixtures=json.loads((Path(__file__).resolve().parents[2]/'shared/adsb-vectors/known-frames.json').read_text())
    decoder=Decoder()
    for v in fixtures['frames']:
        f=decoder.decode(bytes.fromhex(v['hex']),v.get('timestamp',1002))
        assert f is not None
        for key,value in v.items():
            if key not in ('hex','timestamp'):
                if isinstance(value,float):assert getattr(f,key)==pytest.approx(value,abs=.01)
                else:assert getattr(f,key)==value


def test_crc_corruption_rejected():
    raw=bytearray.fromhex(EVEN); raw[6]^=8
    d=Decoder(); assert d.decode(raw) is None; assert d.rejected==1


@pytest.mark.parametrize('raw',[b'',b'\x8d'*7,b'\x8d'*15,encoded(19<<51,df=0x95)])
def test_invalid_length_and_non_icao_df18_ignored(raw):assert Decoder().decode(raw) is None


def test_cpr_expiry_and_newest_frame():
    d=Decoder(); assert d.decode(bytes.fromhex(EVEN),0).latitude is None
    assert d.decode(bytes.fromhex(ODD),11).latitude is None
    f=d.decode(bytes.fromhex(EVEN),12)
    assert f.latitude==pytest.approx(52.257202,abs=.00001)
    assert f.longitude==pytest.approx(3.919373,abs=.00001)


def test_q_zero_altitude_is_unknown():
    me=field(11<<51,9,12,0x800)
    assert Decoder().decode(encoded(me)).altitude is None


@pytest.mark.parametrize('lat,lon',[(-33.9,151.2),(-52.1,-70.3),(52.,-1.5),(86.8,22.5)])
def test_cpr_southern_and_western_hemispheres(lat,lon):
    from skylog.core import nl
    d=Decoder()
    for odd in (0,1):
        dlat=360/(60-odd)
        cprlat=round(131072*((lat % dlat)/dlat))%131072
        dlon=360/max(nl(lat)-odd,1)
        cprlon=round(131072*((lon % dlon)/dlon))%131072
        me=11<<51
        for s,n,v in [(9,12,0xC38),(22,1,odd),(23,17,cprlat),(40,17,cprlon)]:me=field(me,s,n,v)
        f=d.decode(encoded(me),odd)
    assert f.latitude==pytest.approx(lat,abs=.001)
    assert f.longitude==pytest.approx(lon,abs=.003)


def test_velocity_signs_and_unknown_fields():
    me=field(19<<51,6,3,1)
    for s,n,v in [(14,1,1),(15,10,101),(25,1,1),(26,10,101),(37,1,1),(38,9,14)]:me=field(me,s,n,v)
    f=Decoder().decode(encoded(me)); assert f.speed==pytest.approx(141.421,abs=.001)
    assert f.heading==225; assert f.vertical_rate==-832
    f=Decoder().decode(encoded(field(19<<51,6,3,3)))
    assert f.speed is None and f.heading is None and f.vertical_rate is None


@pytest.mark.parametrize('split',[1,7999,16000,16200,16479,16480,16500,16700])
def test_iq_boundary_exactly_once(split):
    data=iq_frame(bytes.fromhex(EVEN),offset=8000)
    d=IqDecoder(); frames=d.feed(data[:split],1)+d.feed(data[split:],1)
    assert len(frames)==1; assert frames[0].icao=='40621D'; assert frames[0].altitude==38000


def test_noise_and_silent_iq_no_false_frames():
    d=IqDecoder(); noise=np.random.default_rng(12).integers(118,138,400000,dtype=np.uint8).tobytes()
    assert d.feed(noise)==[]; assert d.feed(bytes([128])*1000)==[]


def test_tracker_partial_merge_stale_and_expiry():
    t=Tracker(); t.accept(Frame('ABC123',100,1,'TEST',10000,0.,0.))
    t.accept(Frame('ABC123',101,19,speed=100)); t.accept(Frame('ABC123',99,1,'OLD',latitude=1,longitude=1))
    a=t.aircraft['ABC123']; assert a['callsign']=='TEST' and a['latitude']==0 and a['speed']==100
    assert a['frames']==3; assert len(t.live(221))==1; assert t.live(222)==[]; t.live(402); assert not t.aircraft


def test_position_distance_zero_valid():
    assert valid_position(0,0); assert not valid_position(float('nan'),0)
    assert distance_nm(0,0,1,0)==pytest.approx(60.04,abs=.01)


def test_track_bound_preserves_endpoints():
    t=Tracker()
    for i in range(1200):t.accept(Frame('ABC123',i*2,11,latitude=i/100,longitude=0))
    points=t.aircraft['ABC123']['track']
    assert len(points)<=512; assert points[0][0]==0 and points[-1][0]==2398
