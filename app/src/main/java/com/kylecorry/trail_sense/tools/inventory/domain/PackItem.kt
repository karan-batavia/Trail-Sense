package com.kylecorry.trail_sense.tools.inventory.domain

import com.kylecorry.trailsensecore.domain.units.Weight

data class PackItem(
    val id: Int,
    val packId: Int,
    val name: String,
    val category: ItemCategory,
    val amount: Double = 0.0,
    val desiredAmount: Double = 0.0,
    val weight: Weight? = null
)
