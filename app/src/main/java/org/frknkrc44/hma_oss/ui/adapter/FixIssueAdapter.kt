package org.frknkrc44.hma_oss.ui.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import icu.nullptr.hidemyapplist.service.ServiceClient
import icu.nullptr.hidemyapplist.ui.view.ListItemView
import org.frknkrc44.hma_oss.R

@SuppressLint("NotifyDataSetChanged")
@Suppress("DEPRECATION")
class FixIssueAdapter(
    val context: Context,
    val list: List<String>
) : RecyclerView.Adapter<FixIssueAdapter.ViewHolder>()  {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = ListItemView(parent.context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(list[position])

    override fun getItemCount() = list.size

    inner class ViewHolder(val view: ListItemView) : RecyclerView.ViewHolder(view) {
        fun bind(packageName: String) {
            with(context.packageManager) {
                val appInfo = ServiceClient.getPackageInfo(packageName, 0)?.applicationInfo
                view.text = appInfo?.let { getApplicationLabel(it).toString() } ?: packageName
                if (appInfo != null) {
                    view.binding.icon.setImageDrawable(getApplicationIcon(appInfo))
                }
                view.binding.button.isVisible = appInfo != null
            }

            @SuppressLint("UseCompatLoadingForDrawables")
            with(view.binding.button as MaterialButton) {
                if (!isVisible) return
                icon = resources.getDrawable(
                    R.drawable.outline_settings_backup_restore_24,
                    context.theme,
                )
                text = context.getString(R.string.home_migrate_data)

                insetLeft = 0
                insetTop = 0
                insetRight = 0
                insetBottom = 0
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START

                setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.home_migrate_data)
                        .setMessage(context.getString(
                            R.string.sick_mode_notice
                        ) + "\n\n" + context.getString(
                            R.string.home_migrate_data_summary
                        ))
                        .setPositiveButton(R.string.home_migrate_data) { _, _ ->
                            if (ServiceClient.migrateData(packageName)) {
                                ServiceClient.reloadConfigFromFile()
                                showMigrateStatusDialog(true)
                            } else {
                                showMigrateStatusDialog(false)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.home_migrate_uninstall_only) { _, _ ->
                            uninstallPackage(packageName)
                        }
                        .show()
                }
            }
        }
    }

    private fun uninstallPackage(packageName: String) = context.startActivity(
        Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = "package:$packageName".toUri()
        }
    )

    private fun showMigrateStatusDialog(success: Boolean) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.home_migrate_data)
            .setMessage(if (success) {
                R.string.home_migrate_data_completed
            } else {
                R.string.home_migrate_data_failed
            })
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
}
