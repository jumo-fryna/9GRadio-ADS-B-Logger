package com.radiosport.ninegradio.adsblog

import androidx.room.withTransaction
import com.radiosport.ninegradio.data.AppDatabase

/** Consistent report snapshot; include listening sessions even if no aircraft matched. */
object ReceptionReportLoader {
    suspend fun load(db: AppDatabase, filter: LogFilter): ReceptionReport = db.withTransaction {
        val dao=db.adsbLogDao()
        val cards=dao.filteredOnce(filter)
        val sessions=dao.allSessions().filter { s ->
            (filter.sessionId.isBlank()||s.id==filter.sessionId) &&
                (s.endedAt?:s.lastActiveAt)>=filter.from && s.startedAt<filter.until
        }.map { s ->
            val end=minOf(s.endedAt?:s.lastActiveAt,filter.until)
            s.copy(startedAt=maxOf(s.startedAt,filter.from),lastActiveAt=end,
                endedAt=if(s.endedAt!=null||filter.until!=Long.MAX_VALUE)end else null)
        }
        ReceptionReport(System.currentTimeMillis(),sessions,cards,cards.associate{it.reception.id to dao.points(it.reception.id)})
    }
}
