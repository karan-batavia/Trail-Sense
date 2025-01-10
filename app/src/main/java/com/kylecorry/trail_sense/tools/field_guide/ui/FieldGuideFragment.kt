package com.kylecorry.trail_sense.tools.field_guide.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.navigation.fragment.findNavController
import com.kylecorry.andromeda.alerts.dialog
import com.kylecorry.andromeda.core.coroutines.BackgroundMinimumState
import com.kylecorry.andromeda.core.coroutines.onIO
import com.kylecorry.andromeda.core.system.Resources
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.andromeda.fragments.onBackPressed
import com.kylecorry.andromeda.views.list.AsyncListIcon
import com.kylecorry.andromeda.views.list.ListItem
import com.kylecorry.andromeda.views.list.ResourceListIcon
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.FragmentFieldGuideBinding
import com.kylecorry.trail_sense.shared.io.DeleteTempFilesCommand
import com.kylecorry.trail_sense.shared.io.FileSubsystem
import com.kylecorry.trail_sense.shared.views.Views
import com.kylecorry.trail_sense.tools.field_guide.domain.FieldGuidePage
import com.kylecorry.trail_sense.tools.field_guide.domain.FieldGuidePageTag
import com.kylecorry.trail_sense.tools.field_guide.infrastructure.BuiltInFieldGuide

class FieldGuideFragment : BoundFragment<FragmentFieldGuideBinding>() {

    private var species by state<List<FieldGuidePage>>(emptyList())
    private var filter by state("")
    private var tagFilter by state<FieldGuidePageTag?>(null)
    private val files by lazy { FileSubsystem.getInstance(requireContext()) }

    private fun loadFromAssets(): List<FieldGuidePage> {
        return BuiltInFieldGuide.getFieldGuide(requireContext())
    }

    override fun generateBinding(
        layoutInflater: LayoutInflater, container: ViewGroup?
    ): FragmentFieldGuideBinding {
        return FragmentFieldGuideBinding.inflate(layoutInflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        inBackground(BackgroundMinimumState.Created) {
            species = loadFromAssets().sortedBy { it.name }
        }

        binding.search.setOnSearchListener {
            filter = it
        }

        onBackPressed {
            when {
                tagFilter != null -> {
                    tagFilter = null
                }

                else -> {
                    remove()
                    findNavController().navigateUp()
                }
            }
        }
    }

    override fun onUpdate() {
        super.onUpdate()
        effect2(species, filter, tagFilter, lifecycleHookTrigger.onResume()) {
            // TODO: Add a way to select pages that don't have any of these tags
            val tags = listOf(
                FieldGuidePageTag.Plant,
                FieldGuidePageTag.Fungus,
                FieldGuidePageTag.Animal,
                FieldGuidePageTag.Mammal,
                FieldGuidePageTag.Bird,
                FieldGuidePageTag.Reptile,
                FieldGuidePageTag.Amphibian,
                FieldGuidePageTag.Fish,
                FieldGuidePageTag.Invertebrate,
                FieldGuidePageTag.Rock,
                FieldGuidePageTag.Other
            )

            val tagImageMap = mapOf(
                FieldGuidePageTag.Plant to R.drawable.ic_category_natural,
                FieldGuidePageTag.Fungus to R.drawable.mushroom,
                FieldGuidePageTag.Animal to R.drawable.paw,
                FieldGuidePageTag.Mammal to R.drawable.ic_deer,
                FieldGuidePageTag.Bird to R.drawable.bird,
                FieldGuidePageTag.Reptile to R.drawable.lizard,
                FieldGuidePageTag.Amphibian to R.drawable.frog,
                FieldGuidePageTag.Fish to R.drawable.fish,
                FieldGuidePageTag.Invertebrate to R.drawable.ant,
                FieldGuidePageTag.Rock to R.drawable.gem,
                FieldGuidePageTag.Other to R.drawable.ic_help
            )

            val filteredSpecies = species.filter {
                it.name.lowercase().contains(filter.trim()) || it.tags.any { tag ->
                    tag.name.lowercase().contains(filter.trim())
                }
            }.filter { species ->
                tagFilter == null || species.tags.contains(tagFilter)
            }

            val iconTint = Resources.androidTextColorSecondary(requireContext())
            val listItems = if (tagFilter == null && filter.isBlank()) {
                tags.map { tag ->
                    // TODO: Add an icon for each category
                    // TODO: Add a translatable name
                    val numPages = species.count { it.tags.contains(tag) }
                    ListItem(
                        tag.id,
                        tag.name,
                        resources.getQuantityString(
                            R.plurals.page_group_summary,
                            numPages,
                            numPages
                        ),
                        icon = ResourceListIcon(
                            tagImageMap[tag] ?: R.drawable.ic_help,
                            tint = iconTint
                        )
                    ) {
                        tagFilter = tag
                    }
                }
            } else {
                filteredSpecies.map {
                    val firstSentence = it.notes?.substringBefore(".")?.plus(".") ?: ""
                    ListItem(
                        it.id,
                        it.name,
                        firstSentence.take(200),
                        icon = AsyncListIcon(
                            viewLifecycleOwner,
                            { loadThumbnail(it) },
                            size = 48f,
                            scaleType = ImageView.ScaleType.CENTER_CROP,
                            clearOnPause = true
                        ),
                    ) {
                        // TODO: Open a separate page
                        dialog(
                            it.name,
                            it.notes ?: "",
                            allowLinks = true,
                            contentView = Views.image(
                                requireContext(),
                                files.drawable(it.images.first()),
                                width = ViewGroup.LayoutParams.MATCH_PARENT,
                                height = Resources.dp(requireContext(), 200f).toInt()
                            ),
                            scrollable = true
                        )
                    }
                }
            }

            binding.list.setItems(listItems)
        }
    }

    private suspend fun loadThumbnail(species: FieldGuidePage): Bitmap = onIO {
        val size = Resources.dp(requireContext(), 48f).toInt()
        try {
            files.bitmap(species.images.first(), Size(size, size)) ?: getDefaultThumbnail()
        } catch (e: Exception) {
            getDefaultThumbnail()
        }
    }

    private fun getDefaultThumbnail(): Bitmap {
        val size = Resources.dp(requireContext(), 48f).toInt()
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    }

    override fun onDestroy() {
        super.onDestroy()
        inBackground {
            DeleteTempFilesCommand(requireContext()).execute()
        }
    }
}