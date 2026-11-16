package com.marketsboard.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketsboard.ui.Dash
import com.marketsboard.ui.DemoData
import com.marketsboard.ui.Hairline
import com.marketsboard.ui.Label
import com.marketsboard.ui.ScreenTitle
import com.marketsboard.ui.formatTimeAgo

/** M0 placeholder: three headline rows from [DemoData]. */
@Composable
fun NewsScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenTitle("News", Modifier.padding(top = 18.dp))
            Text("Demo headlines", color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        item { Hairline() }
        DemoData.headlines.forEach { h ->
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Label(h.category)
                    Text(
                        h.title, color = Dash.text, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(formatTimeAgo(h.ageSec), color = Dash.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Hairline(Modifier.padding(top = 12.dp))
            }
        }
        item {
            Text(
                "Placeholder content. Real feeds arrive in a later update.",
                color = Dash.faint, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}
