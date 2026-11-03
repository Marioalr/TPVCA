package io.github.marioalr.tpvca.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes

/** Cómo se acomoda la pantalla: la tablet usa dos columnas y el celular una. El rol del equipo sale de DeviceRegistry (K3). */
enum class HomeLayout {
    Wide,
    Narrow,
    ;

    companion object {
        /** Compact (celular en vertical) es angosta; Medium y Expanded (tablet, celular en horizontal) son anchas. */
        fun from(widthSizeClass: WindowWidthSizeClass): HomeLayout =
            if (widthSizeClass == WindowWidthSizeClass.Compact) Narrow else Wide
    }
}

object HomeTags {
    const val WIDE = "home-wide"
    const val NARROW = "home-narrow"
    const val LINK_BUTTON = "home-link-button"
}

/**
 * Inicio de K0: qué app es, su versión, la revisión del contrato que entiende y que todavía no está vinculada. «Vincular con la
 * PC» se activa en K3.
 */
@Composable
fun HomeScreen(versionName: String, contractRevision: Int, layout: HomeLayout, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val scroll = rememberScrollState()
        when (layout) {
            HomeLayout.Wide -> Row(
                modifier = Modifier
                    .safeDrawingPadding()
                    .verticalScroll(scroll)
                    .padding(32.dp)
                    .testTag(HomeTags.WIDE),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                HomeCard(Modifier.weight(1f)) { AppIdentity(versionName, contractRevision) }
                HomeCard(Modifier.weight(1f)) { LinkStatus() }
            }
            HomeLayout.Narrow -> Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .verticalScroll(scroll)
                    .padding(16.dp)
                    .testTag(HomeTags.NARROW),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HomeCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) { AppIdentity(versionName, contractRevision) }
                HomeCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) { LinkStatus() }
            }
        }
    }
}

@Composable
private fun HomeCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun AppIdentity(versionName: String, contractRevision: Int) {
    Text(
        text = stringResource(R.string.home_title),
        style = MaterialTheme.typography.displaySmall,
        color = LocalTpvcColors.current.accentForeground,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.home_version, versionName), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.home_contract_revision, contractRevision),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkStatus() {
    val colors = LocalTpvcColors.current
    Surface(color = colors.infoSubtle, contentColor = colors.infoText, shape = MaterialTheme.shapes.medium) {
        Text(
            text = stringResource(R.string.home_not_linked),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        )
    }
    Text(
        stringResource(R.string.home_link_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    // Se activa en K3 (pegar o escanear la URL y el token, y esperar la aprobación de la PC)
    Button(
        onClick = {},
        enabled = false,
        modifier = Modifier.heightIn(min = 48.dp).testTag(HomeTags.LINK_BUTTON),
    ) {
        Text(stringResource(R.string.home_link_button))
    }
}

@Preview(name = "Celular · claro", widthDp = 360, heightDp = 720)
@Composable
private fun HomeNarrowPreview() {
    TpvcTheme(TpvcThemes.claro) { HomeScreen("0.1.0", 20, HomeLayout.Narrow) }
}

@Preview(name = "Tablet · oscuro", widthDp = 1024, heightDp = 640)
@Composable
private fun HomeWideDarkPreview() {
    TpvcTheme(TpvcThemes.oscuro) { HomeScreen("0.1.0", 20, HomeLayout.Wide) }
}

@Preview(name = "Tablet · ámbar", widthDp = 1024, heightDp = 640)
@Composable
private fun HomeWideAmberPreview() {
    TpvcTheme(TpvcThemes.ambar()) { HomeScreen("0.1.0", 20, HomeLayout.Wide) }
}
