package com.megwil

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun RobotReactor(mode: String, modifier: Modifier = Modifier) {
 val t = rememberInfiniteTransition(label="reactor")
 val pulse by t.animateFloat(.84f, 1.08f, infiniteRepeatable(tween(1100, easing=FastOutSlowInEasing), RepeatMode.Reverse), label="pulse")
 val active = mode == "LISTENING" || mode == "SPEAKING"
 val cyan=Color(0xFF00E5FF); val violet=Color(0xFF8B5CFF)
 Canvas(modifier.size(190.dp)) {
  val x=size.width/2; val y=size.height/2; val u=size.minDimension/190f; val p=if(active) pulse else .92f
  drawCircle(Brush.radialGradient(listOf(cyan.copy(alpha=if(active) .34f else .14f), violet.copy(alpha=.08f), Color.Transparent), Offset(x,y), 90*u*p), 90*u*p, Offset(x,y))
  drawCircle(cyan.copy(alpha=.8f),75*u,Offset(x,y),style=Stroke(1.8f*u))
  drawCircle(violet.copy(alpha=if(active) .95f else .5f),82*u*p,Offset(x,y),style=Stroke(2.2f*u))
  drawCircle(cyan.copy(alpha=.35f),88*u*p,Offset(x,y),style=Stroke(1*u))
  drawRoundRect(Brush.linearGradient(listOf(Color(0xFF263D59),Color(0xFF071321),Color(0xFF142C45))),Offset(x-52*u,y-56*u),Size(104*u,112*u),androidx.compose.ui.geometry.CornerRadius(34*u))
  drawRoundRect(cyan,Offset(x-52*u,y-56*u),Size(104*u,112*u),androidx.compose.ui.geometry.CornerRadius(34*u),style=Stroke(1.5f*u))
  drawRoundRect(Color(0xFF020914),Offset(x-39*u,y-28*u),Size(78*u,59*u),androidx.compose.ui.geometry.CornerRadius(22*u))
  drawRoundRect(Brush.horizontalGradient(listOf(cyan,Color.White,cyan)),Offset(x-29*u,y-9*u),Size(22*u,7*u),androidx.compose.ui.geometry.CornerRadius(4*u))
  drawRoundRect(Brush.horizontalGradient(listOf(cyan,Color.White,cyan)),Offset(x+7*u,y-9*u),Size(22*u,7*u),androidx.compose.ui.geometry.CornerRadius(4*u))
  drawLine(cyan,Offset(x-13*u,y+22*u),Offset(x+13*u,y+22*u),2*u,cap=StrokeCap.Round)
  drawCircle(violet,4*u,Offset(x,y-44*u))
 }
}
