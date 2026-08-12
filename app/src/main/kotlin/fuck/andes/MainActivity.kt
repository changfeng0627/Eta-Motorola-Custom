package fuck.andes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import fuck.andes.ui.theme.EtaMotorolaCustomTheme
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.view.View
import android.widget.Switch
import android.widget.CompoundButton
import android.widget.Toast

class MainActivity : ComponentActivity() {
    
    private lateinit var tianxiAiSwitch: Switch
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 创建简单的UI界面
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        
        // 标题
        val titleText = TextView(this).apply {
            text = "ETA Motorla Custom"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(titleText)
        
        // 描述
        val descriptionText = TextView(this).apply {
            text = "为Motorola XT2611-1定制的ETA版本，支持天禧AI替换"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(descriptionText)
        
        // 天禧AI开关
        val switchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        
        val switchLabel = TextView(this).apply {
            text = "启用天禧AI Hook"
            textSize = 18f
        }
        switchLayout.addView(switchLabel)
        
        tianxiAiSwitch = Switch(this).apply {
            isChecked = true
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                val status = if (isChecked) "已启用" else "已禁用"
                Toast.makeText(this@MainActivity, "天禧AI Hook $status", Toast.LENGTH_SHORT).show()
            }
        }
        switchLayout.addView(tianxiAiSwitch)
        
        layout.addView(switchLayout)
        
        // 设置按钮
        val settingsButton = Button(this).apply {
            text = "打开天禧AI设置"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TianxiAISettingsActivity::class.java)
                startActivity(intent)
            }
        }
        layout.addView(settingsButton)
        
        // 状态信息
        val statusText = TextView(this).apply {
            text = "包名: com.custom.eta.motorola\n目标应用: com.lenovo.xbb (天禧AI)"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        layout.addView(statusText)
        
        setContentView(layout)
    }
}