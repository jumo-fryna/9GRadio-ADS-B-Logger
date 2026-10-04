#!/usr/bin/env python3
"""Execute production migration/filter SQL in SQLite; not a replacement for Room/device tests."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (ROOT / 'app/src/main/java/com/radiosport/ninegradio/adsblog/LogMigration.kt').read_text()
SQL = re.findall(r'"""(.*?)"""|"(CREATE INDEX[^"\n]*)"', MIGRATION, re.S)
STATEMENTS = [a or b for a, b in SQL]
ENTITIES = (ROOT / 'app/src/main/java/com/radiosport/ninegradio/adsblog/LogEntities.kt').read_text()
FILTER = next(q for q in re.findall(r'@Query\("""(.*?)"""\)', ENTITIES, re.S) if 'SELECT r.*' in q)

class ProductionSqlTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys = ON')
        self.db.execute('CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, label TEXT NOT NULL)')
        self.db.execute("INSERT INTO bookmarks VALUES(7,'keep this radio bookmark')")
        for statement in STATEMENTS:
            self.db.execute(statement)
        self.db.execute("INSERT INTO adsb_sessions VALUES('s',0,1000,NULL,52,21)")
        self.db.execute("""INSERT INTO adsb_receptions
            (id,sessionId,icao24,callsign,firstSeen,lastSeen,frameCount,maxDistanceNm)
            VALUES('r','s','ABC123','LOT_123',100,1000,42,123.5)""")
        self.db.execute("""INSERT INTO adsb_identity VALUES
            ('ABC123','SP-ABC','Boeing','737','LOT','B738','local dataset',1000)""")
    def tearDown(self):
        self.db.close()
    def select(self, **kwargs):
        params = dict(from_=0, until=2000, sessionId='', icao='', callsign='', registration='', operator='', type='')
        params.update(kwargs); params['from'] = params.pop('from_')
        return self.db.execute(FILTER, params).fetchall()
    def test_additive_migration_preserves_old_tables(self):
        self.assertEqual('keep this radio bookmark', self.db.execute('SELECT label FROM bookmarks WHERE id=7').fetchone()[0])
        self.assertEqual(7, len(STATEMENTS))
    def test_all_filters_and_metadata_join(self):
        self.assertEqual(1, len(self.select(icao='abc', callsign='LOT', registration='SP-', operator='lot', type='B738')))
        self.assertEqual(1, len(self.select(type='737')))
        self.assertEqual([], self.select(operator='Other'))
        self.assertEqual([], self.select(sessionId='other-session'))
    def test_half_open_date_window_and_reception_overlap(self):
        self.assertEqual([], self.select(until=100))
        self.assertEqual(1, len(self.select(from_=500, until=600)))
        self.assertEqual([], self.select(from_=1001))
    def test_literal_like_escape(self):
        self.assertEqual(1, len(self.select(callsign=r'LOT\_123')))
        self.assertEqual([], self.select(callsign=r'LOT\%'))
    def test_cascade_does_not_touch_radio_data_or_identity_cache(self):
        self.db.execute("INSERT INTO adsb_points VALUES('r',0,1000,52,21,NULL)")
        self.db.execute("DELETE FROM adsb_sessions WHERE id='s'")
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM adsb_receptions').fetchone()[0])
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM adsb_points').fetchone()[0])
        self.assertEqual(1, self.db.execute('SELECT COUNT(*) FROM adsb_identity').fetchone()[0])
        self.assertEqual(1, self.db.execute('SELECT COUNT(*) FROM bookmarks').fetchone()[0])
    def test_orphan_point_is_rejected(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO adsb_points VALUES('missing',0,1000,52,21,NULL)")
    def test_production_one_to_two_to_three_preserves_legacy_records(self):
        fixture = (ROOT / 'app/src/androidTest/java/com/radiosport/ninegradio/adsblog/LogMigrationTest.kt').read_text()
        base = fixture.split('private fun legacyTables')[1].split('if (version == 1)')[0]
        tables = re.findall(r'"(CREATE TABLE[^"\n]*)"', base)
        bookmark = re.findall(r'"(CREATE TABLE bookmarks[^"\n]*)"', fixture)[0]
        app = (ROOT / 'app/src/main/java/com/radiosport/ninegradio/data/AppDatabase.kt').read_text()
        migration = app.split('val MIGRATION_1_2')[1].split('fun getDatabase')[0]
        one_two = [a or b for a, b in re.findall(r'database\.execSQL\("""(.*?)"""\)|database\.execSQL\("([^"\n]*)"\)', migration, re.S)]
        db = sqlite3.connect(':memory:')
        try:
            db.execute('PRAGMA foreign_keys = ON')
            for statement in tables + [bookmark]: db.execute(statement)
            db.execute("INSERT INTO bookmarks VALUES(7,1090000000,'legacy bookmark',123,1000)")
            db.execute("INSERT INTO memory_channels VALUES(9,'Saved memory',145500000,'NFM',1920000,26,-100,0,0,0,'Default','preserve',1000,1000)")
            for statement in one_two + STATEMENTS: db.execute(statement)
            self.assertEqual(('legacy bookmark', 1090000000, 123), db.execute('SELECT label,frequencyHz,color FROM bookmarks WHERE id=7').fetchone())
            self.assertEqual('Saved memory', db.execute('SELECT name FROM memory_channels WHERE id=9').fetchone()[0])
            self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
        finally: db.close()

    def test_expected_schema_nullability_indexes_and_utc_fields(self):
        columns = {r[1]: r for r in self.db.execute('PRAGMA table_info(adsb_receptions)')}
        for field in ['id','sessionId','icao24','firstSeen','lastSeen','frameCount']:
            self.assertEqual(1, columns[field][3])
        for field in ['callsign','latitude','longitude','maxDistanceNm','onGround']:
            self.assertEqual(0, columns[field][3])
        indexes = {r[1] for r in self.db.execute('PRAGMA index_list(adsb_receptions)')}
        self.assertTrue({'index_adsb_receptions_sessionId','index_adsb_receptions_icao24','index_adsb_receptions_firstSeen'} <= indexes)

if __name__ == '__main__':
    unittest.main(verbosity=2)
