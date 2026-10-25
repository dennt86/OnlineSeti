package app.qthrone.mobile.bg

import java.io.Closeable

interface AbstractInstance : Closeable {

    fun launch()

}