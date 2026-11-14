package com.ansardeo.bnb.paper

import android.content.Context
import com.ansardeo.bnb.data.TradingStage
import com.ansardeo.bnb.paper.db.PaperDatabase

/** Provides the single Stage 1 (Virtual) engine. Stage 2/3 have no engine in this build. */
object PaperEngineProvider {
    @Volatile private var stage1: PaperEngine? = null

    fun get(context: Context, stage: TradingStage = TradingStage.STAGE_1): PaperEngine {
        check(stage == TradingStage.STAGE_1) { "${stage.title}: no execution engine exists for this stage in this build." }
        val db = PaperDatabase.get(context.applicationContext)
        return stage1 ?: synchronized(this) { stage1 ?: PaperEngine(db, TradingStage.STAGE_1).also { stage1 = it } }
    }

    fun resetInstances() {
        synchronized(this) { stage1 = null }
    }
}
