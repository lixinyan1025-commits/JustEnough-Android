package com.justenough.planner.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.justenough.planner.appContainer
import com.justenough.planner.data.*
import com.justenough.planner.ui.TaskFinishActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import android.content.res.Configuration

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = withContext(Dispatchers.IO) { context.appContainer.repository.todaySnapshot() }
        val settings = context.appContainer.settings.state.first()
        val darkSystem = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val darkText = settings.widgetTextMode == "DARK" || (settings.widgetTextMode == "SYSTEM" && !darkSystem)
        val contrast = settings.widgetContrast.coerceIn(.55f,1f)
        val mainColor = (if (darkText) Color.Black else Color.White).copy(alpha=contrast)
        val mutedColor = (if (darkText) Color.Black else Color.White).copy(alpha=(contrast*.76f).coerceAtMost(1f))
        provideContent { Content(snapshot, mainColor, mutedColor, settings.widgetFontScale,settings.subtleBacking) }
    }
}
class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TodayWidget() }
@Composable private fun Content(s:TodaySnapshot,mainValue:Color,mutedValue:Color,fontScale:Float,backing:Boolean){val ctx=LocalContext.current;val main=ColorProvider(mainValue);val muted=ColorProvider(mutedValue);val root=if(backing)GlanceModifier.fillMaxSize().background(ColorProvider(Color(0x26000000))).padding(16.dp)else GlanceModifier.fillMaxSize().padding(16.dp);LazyColumn(root){
    item{Row{repeat(5){i->Text(if(i<s.fulfillmentTotal)"■" else "□",GlanceModifier.padding(end=5.dp),TextStyle(main,(18*fontScale).sp,FontWeight.Bold))}};if(s.fulfillmentTotal>5)Text("溢出 +${s.fulfillmentTotal-5}",style=TextStyle(muted,(12*fontScale).sp))}
    items(s.todayBlocks,itemId={it.block.id}){b->Column(GlanceModifier.fillMaxWidth().padding(vertical=7.dp)){Text("${clock(b.block.startMinute)}–${clock(b.block.endMinute)}  ${b.block.name}",style=TextStyle(muted,(12*fontScale).sp));b.plans.forEach{p->val completed=p.id in s.completedPlanIds;val active=s.activeRun?.takeIf{it.planId==p.id};Text("${if(completed)"✓" else if(active!=null)"◉" else "○"} ${p.name}",GlanceModifier.fillMaxWidth().padding(top=7.dp,bottom=7.dp).let{m->when{completed->m;active!=null->m.clickable(actionStartActivity(Intent(ctx,TaskFinishActivity::class.java).putExtra("run_id",active.runId)));else->m.clickable(actionStartActivity(Intent(ctx,WidgetTaskActivity::class.java).putExtra("plan_id",p.id))) }},TextStyle(if(completed)muted else main,(15*fontScale).sp,FontWeight.Medium))}}}
}}
private fun clock(m:Int)="%02d:%02d".format(m/60,m%60)
