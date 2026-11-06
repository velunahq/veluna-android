package com.veluna.models

import com.music.innertube.models.YTItem
import com.veluna.db.entities.LocalItem

data class SimilarRecommendation(
  val title: LocalItem,
  val items: List<YTItem>,
)
