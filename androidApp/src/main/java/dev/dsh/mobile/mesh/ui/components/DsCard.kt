package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.dsh.mobile.mesh.ui.theme.DsSpacing

/**
 * A grouped surface: a Material outlined card.
 *
 * The outline is not decoration. In the light theme the page and its cards are both pure white, so
 * a card drawn with fill alone is invisible — the border is the only thing separating it from the
 * page. [MaterialTheme.shapes.medium] matches the other cards in the app.
 */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(DsSpacing.tiny),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        OutlinedCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            CardBody(verticalArrangement, content)
        }
    } else {
        OutlinedCard(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            CardBody(verticalArrangement, content)
        }
    }
}

@Composable
private fun CardBody(
    verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
