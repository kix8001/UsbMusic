package app.mileage.log.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mileage.log.Category

fun categoryColor(c: Category): Color = when (c) {
    Category.COMMUTE -> Color(0xFF4FC3F7)
    Category.BUSINESS -> Color(0xFFFFB74D)
    Category.PRIVATE -> Color(0xFF9E9E9E)
    Category.UNSET -> Color(0xFFEF5350)
}

/** 区分を選ぶチップの列 */
@Composable
fun CategoryPicker(selected: Category, onSelect: (Category) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(Category.COMMUTE, Category.BUSINESS, Category.PRIVATE, Category.UNSET).forEach { c ->
            FilterChip(selected = selected == c, onClick = { onSelect(c) }, label = { Text(c.label) })
        }
    }
}

/** 一覧に出す小さな区分ラベル */
@Composable
fun CategoryBadge(c: Category) {
    Text(
        c.label,
        color = Color.Black,
        fontSize = 11.sp,
        modifier = Modifier
            .background(categoryColor(c), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

fun fmtYen(v: Long): String = "¥" + String.format(java.util.Locale.JAPAN, "%,d", v)
