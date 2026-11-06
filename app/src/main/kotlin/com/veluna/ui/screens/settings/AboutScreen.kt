@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.veluna.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.veluna.BuildConfig
import com.veluna.LocalPlayerAwareWindowInsets
import com.veluna.R
import com.veluna.ui.component.IconButton
import com.veluna.ui.component.Material3SettingsGroup
import com.veluna.ui.component.Material3SettingsItem
import com.veluna.ui.utils.backToMain
import com.veluna.ui.utils.safeOpenUri

private const val GITHUB_REPO_URL = "https://github.com/velunahq/veluna-android"
private const val ISSUES_URL = "https://github.com/velunahq/veluna-android/issues"
private const val WEBSITE_URL = "https://velunahq.github.io"
private const val DISCUSSIONS_URL = "https://github.com/velunahq/veluna-android/discussions"
private const val AUTHOR_URL = "https://github.com/rry0ku"
private const val LICENSE_URL = "https://github.com/velunahq/veluna-android/blob/main/LICENSE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
  onBack: (() -> Unit)? = null,
  highlightKey: String? = null
) {
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current

  val communityItems = listOf(
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_github_repo)),
      icon = painterResource(R.drawable.github),
      title = { Text(stringResource(R.string.about_github_repo)) },
      description = { Text(stringResource(R.string.about_github_repo_desc)) },
      onClick = { uriHandler.safeOpenUri(context, GITHUB_REPO_URL) }
    ),
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_issues)),
      icon = painterResource(R.drawable.bug_report),
      title = { Text(stringResource(R.string.about_issues)) },
      description = { Text(stringResource(R.string.about_issues_desc)) },
      onClick = { uriHandler.safeOpenUri(context, ISSUES_URL) }
    ),
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_website)),
      icon = painterResource(R.drawable.website),
      title = { Text(stringResource(R.string.about_website)) },
      description = { Text(stringResource(R.string.about_website_desc)) },
      onClick = { uriHandler.safeOpenUri(context, WEBSITE_URL) }
    ),
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_discussions)),
      icon = painterResource(R.drawable.group_outlined),
      title = { Text(stringResource(R.string.about_discussions)) },
      description = { Text(stringResource(R.string.about_discussions_desc)) },
      onClick = { uriHandler.safeOpenUri(context, DISCUSSIONS_URL) }
    )
  )

  val projectItems = listOf(
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_author)),
      icon = painterResource(R.drawable.person),
      title = { Text(stringResource(R.string.about_author)) },
      description = { Text(stringResource(R.string.about_author_desc)) },
      onClick = { uriHandler.safeOpenUri(context, AUTHOR_URL) }
    ),
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_changelog)),
      icon = painterResource(R.drawable.new_release),
      title = { Text(stringResource(R.string.about_changelog)) },
      description = { Text(stringResource(R.string.about_changelog_desc)) },
      onClick = { navController.navigate("settings/changelog") }
    ),
    Material3SettingsItem(
      isHighlighted = (highlightKey == stringResource(R.string.about_license)),
      icon = painterResource(R.drawable.info),
      title = { Text(stringResource(R.string.about_license)) },
      description = { Text(stringResource(R.string.about_license_desc)) },
      onClick = { uriHandler.safeOpenUri(context, LICENSE_URL) }
    )
  )

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.surface,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = stringResource(R.string.about),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(
            onClick = { onBack?.invoke() ?: navController.navigateUp() },
            onLongClick = navController::backToMain,
          ) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
          }
        },
        windowInsets = TopAppBarDefaults.windowInsets,
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
          ),
        scrollBehavior = scrollBehavior,
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier =
        Modifier.fillMaxSize()
          .windowInsetsPadding(
            LocalPlayerAwareWindowInsets.current.only(
              WindowInsetsSides.Horizontal,
            ),
          ),
      contentPadding =
        PaddingValues(
          start = 16.dp,
          top = innerPadding.calculateTopPadding() + 8.dp,
          end = 16.dp,
          bottom =
            androidx.compose.foundation.layout.WindowInsets.systemBars
              .asPaddingValues()
              .calculateBottomPadding() + 32.dp,
        ),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item { AboutAppCard() }
      item {
        Material3SettingsGroup(
          title = stringResource(R.string.about_community_header),
          items = communityItems
        )
      }
      item {
        Material3SettingsGroup(
          title = stringResource(R.string.about_project_header),
          items = projectItems
        )
      }
    }
  }
}

@Composable
private fun AboutAppCard() {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier.size(96.dp).clip(CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Image(
        painter = painterResource(R.drawable.ic_launcher_nobg),
        contentDescription = "Veluna Logo",
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxSize()
      )
    }

    Spacer(Modifier.height(4.dp))

    Text(
      text = "Veluna",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
      ) {
        Text(
          text = "0.1.0",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
      }
      if (BuildConfig.DEBUG) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        ) {
          Text(
            text = "DEBUG",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      } else {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
        ) {
          Text(
            text = BuildConfig.ARCHITECTURE.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }
    }
  }
}
