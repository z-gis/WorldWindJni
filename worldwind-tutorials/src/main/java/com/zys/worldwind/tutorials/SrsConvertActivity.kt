package com.zys.worldwind.tutorials

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zys.worldwindjni.NativeSrs

/**
 * 演示 06 · 坐标转换（PROJ）：
 *  - [NativeSrs.initProjData]：解压内置 proj.db 并设置搜索路径（首次转换前调一次）；
 *  - [NativeSrs.convert]：任意 EPSG 坐标系互转（WGS84 ↔ Web Mercator 等），
 *    转换不可用返回 null 由调用方回退；
 *  - [NativeSrs.getProjVersion]：PROJ 主版本号。
 */
class SrsConvertActivity : AppCompatActivity() {

    private var srcCrs = "EPSG:4326"
    private var tgtCrs = "EPSG:3857"

    private lateinit var crsText: TextView
    private lateinit var lonEdit: EditText
    private lateinit var latEdit: EditText
    private lateinit var resultText: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeSrs.initProjData(this)

        crsText = TextView(this).apply {
            text = "源：$srcCrs   →   目标：$tgtCrs"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        lonEdit = editText("经度（lon）", 116.4)
        latEdit = editText("纬度（lat）", 39.9)
        resultText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }

        val convertBtn = Button(this).apply {
            text = getString(R.string.action_convert)
            isAllCaps = false
            setOnClickListener { doConvert() }
        }
        val swapBtn = Button(this).apply {
            text = "交换源/目标"
            isAllCaps = false
            setOnClickListener {
                val s = srcCrs; srcCrs = tgtCrs; tgtCrs = s
                crsText.text = "源：$srcCrs   →   目标：$tgtCrs"
                // 换向后把上次结果填入输入框，便于目检往返一致性
                resultText.text = ""
            }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(48), dp(20), dp(20))
                addView(TextView(context).apply {
                    text = getString(R.string.demo_srs_title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(crsText)
                addView(lonEdit)
                addView(latEdit)
                addView(convertBtn)
                addView(swapBtn)
                addView(resultText)
            })
        })
    }

    private fun doConvert() {
        val lon = lonEdit.text.toString().toDoubleOrNull()
        val lat = latEdit.text.toString().toDoubleOrNull()
        if (lon == null || lat == null) {
            resultText.text = "请输入合法数值"
            return
        }
        val out = NativeSrs.convert(lon, lat, srcCrs, tgtCrs)
        resultText.text = if (out == null) {
            "转换失败（返回 null）：proj.db 未就绪或编码非法\nPROJ ${NativeSrs.getProjVersion()}"
        } else {
            "($lon, $lat)  $srcCrs → $tgtCrs\n⇒ x=%.3f  y=%.3f\nPROJ 主版本：${NativeSrs.getProjVersion()}".format(out[0], out[1])
        }
    }

    private fun editText(hint: String, default: Double) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(default.toString())
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
