package com.ansardeo.bnb

import android.app.Application

/**
 * Background monitoring is owned by [com.ansardeo.bnb.paper.PaperMonitorService] (foreground service), started from MainActivity and
 * restarted by Android where permitted. The Application object itself no longer runs the monitor loop.
 */
class App : Application()
