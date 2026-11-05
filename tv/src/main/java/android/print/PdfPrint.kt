package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Nằm trong package android.print để truy cập được constructor package-private của
 * LayoutResultCallback / WriteResultCallback -> ghi PDF thẳng ra file, không hiện hộp thoại in.
 */
class PdfPrint(private val attrs: PrintAttributes) {
    fun print(adapter: PrintDocumentAdapter, file: File, done: (Boolean, String?) -> Unit) {
        adapter.onLayout(null, attrs, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                try {
                    val pfd = ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                    )
                    adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                        object : PrintDocumentAdapter.WriteResultCallback() {
                            override fun onWriteFinished(pages: Array<out PageRange>?) {
                                try { pfd.close() } catch (_: Exception) {}
                                done(true, null)
                            }
                            override fun onWriteFailed(error: CharSequence?) {
                                try { pfd.close() } catch (_: Exception) {}
                                done(false, error?.toString())
                            }
                        })
                } catch (e: Throwable) {
                    done(false, e.message)
                }
            }
            override fun onLayoutFailed(error: CharSequence?) { done(false, error?.toString()) }
        }, null)
    }
}
