package Com.hau.name

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * App chỉ có 1 vai trò: Máy Camera (điện thoại làm webcam cho Qrtuxa trên máy tính).
 * Mở thẳng vào CameraActivity, không cần chọn vai trò.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, CameraActivity::class.java))
        finish()
    }
}
