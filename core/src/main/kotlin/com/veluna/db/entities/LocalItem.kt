package com.veluna.db.entities

sealed class LocalItem {
  abstract val id: String
  abstract val title: String
  abstract val thumbnailUrl: String?
}
