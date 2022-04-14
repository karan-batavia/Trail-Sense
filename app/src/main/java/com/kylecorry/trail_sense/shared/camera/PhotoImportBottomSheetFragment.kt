package com.kylecorry.trail_sense.shared.camera

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.*
import androidx.annotation.RequiresApi
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.getSystemService
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kylecorry.andromeda.fragments.BoundBottomSheetDialogFragment
import com.kylecorry.trail_sense.databinding.FragmentPhotoImportSheetBinding


class PhotoImportBottomSheetFragment(
    private val resolution: Size? = null,
    private val onCapture: (bitmap: Bitmap?) -> Unit
) : BoundBottomSheetDialogFragment<FragmentPhotoImportSheetBinding>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.camera.clipToOutline = true
        binding.camera.start(resolution)

        binding.toolTitle.rightQuickAction.setOnClickListener {
            onCapture(null)
            dismiss()
        }

        binding.captureButton.setOnClickListener {
            binding.camera.capture {
                onCapture(it)
                dismiss()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener {
            val sheetDialog = it as BottomSheetDialog
            val root =
                sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
            val behavior = BottomSheetBehavior.from(root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            root.layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                getWindowHeight()
            )
        }
        return dialog
    }

    // TODO: Move to Andromeda
    private fun getWindowHeight(): Int {
        return getWindowSize().height
    }

    private fun getWindowSize(): Size {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            getWindowSizeSDK30()
        } else {
            getWindowSizeLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun getWindowSizeLegacy(): Size {
        val window = requireContext().getSystemService<WindowManager>()!!
        val point = Point()
        window.defaultDisplay.getSize(point)
        return Size(point.x, point.y)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getWindowSizeSDK30(): Size {
        val window = requireContext().getSystemService<WindowManager>()!!
        val metrics = window.currentWindowMetrics
        val windowInsets = metrics.windowInsets
        val insets = windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.navigationBars()
                    or WindowInsets.Type.displayCutout()
        )

        val insetsWidth = insets.right + insets.left
        val insetsHeight = insets.top + insets.bottom
        val bounds = metrics.bounds
        return Size(
            bounds.width() - insetsWidth,
            bounds.height() - insetsHeight
        )
    }

    override fun onDestroyView() {
        if (isBound) {
            binding.camera.stop()
        }
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        binding.camera.stop()
    }

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentPhotoImportSheetBinding {
        return FragmentPhotoImportSheetBinding.inflate(layoutInflater, container, false)
    }
}