package com.charon.billiard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var game: PoolSurface
    private lateinit var hud: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        game = PoolSurface(this)
        root.addView(game)
        hud = TextView(this).apply {
            text = "CHARON  •  8 BALL     TOP VIEW"
            textSize = 14f; setTextColor(0xffe8edf0.toInt())
            setPadding(24, 18, 24, 18); setBackgroundColor(0xaa101820.toInt())
        }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val camera = TextView(this).apply {
            text = "КАМЕРА  ↗"; textSize = 13f; setTextColor(-1)
            setPadding(22, 16, 22, 16); setBackgroundColor(0xdd28343d.toInt())
            setOnClickListener { game.renderer.toggleCamera(); text = if (game.renderer.firstPerson) "ВИД СВЕРХУ" else "ВИД ОТ КИЯ" }
        }
        root.addView(camera, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        val shoot = TextView(this).apply {
            text = "УДАР  ●"; textSize = 16f; setTextColor(-1)
            setPadding(30, 20, 30, 20); setBackgroundColor(0xff8b673f.toInt())
            setOnClickListener { game.renderer.shoot() }
        }
        root.addView(shoot, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 24 })
        val info = TextView(this).apply {
            text = "ПЕРЕТАЩИ КИЙ ДЛЯ ПРИЦЕЛА  •  ТАП — СИЛА УДАРА"
            textSize = 11f; setTextColor(0xffc4cdd2.toInt()); setPadding(18, 12, 18, 12)
            setBackgroundColor(0xaa101820.toInt())
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
    }
}

class PoolSurface(ctx: android.content.Context) : GLSurfaceView(ctx) {
    val renderer = PoolRenderer()
    private var lastX = 0f
    init { setEGLContextClientVersion(2); setRenderer(renderer); renderMode = RENDERMODE_CONTINUOUSLY }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; return true }
            MotionEvent.ACTION_MOVE -> { renderer.aim((e.x-lastX)/width.coerceAtLeast(1)); lastX=e.x; return true }
            MotionEvent.ACTION_UP -> { renderer.power = (0.35f + abs(e.y-height/2)/height).coerceIn(.25f,1f); return true }
        }; return true
    }
}

data class Ball(var x: Float, var z: Float, var vx: Float=0f, var vz: Float=0f, val color: FloatArray, val number:Int)
class PoolRenderer : GLSurfaceView.Renderer {
    @Volatile var firstPerson=false
    @Volatile var power=.65f
    private var angle=0f
    private var program=0
    private var aPos=0; private var uMvp=0; private var uColor=0
    private var projection=FloatArray(16); private var view=FloatArray(16); private var model=FloatArray(16); private var mvp=FloatArray(16)
    private lateinit var sphere: FloatBuffer
    private var sphereCount=0
    private val balls= mutableListOf<Ball>()
    private val radius=.075f
    private val friction=.992f
    private var last=System.nanoTime()
    init { resetBalls() }
    fun toggleCamera(){ firstPerson=!firstPerson }
    fun aim(delta:Float){ angle=(angle+delta*2.2f).coerceIn(-1.2f,1.2f) }
    fun shoot(){ val cue=balls.firstOrNull{it.number==0} ?: return; cue.vx=sin(angle)*1.55f*power; cue.vz=-cos(angle)*1.55f*power }
    private fun resetBalls(){
        balls.clear()
        balls.add(Ball(0f,.62f,color=color(0.94f,.94f,.88f),number=0))
        val palette=arrayOf(color(.95f,.78f,.12f),color(.12f,.2f,.75f),color(.85f,.12f,.12f),color(.45f,.12f,.58f),color(.95f,.48f,.08f),color(.1f,.55f,.3f),color(.45f,.2f,.1f))
        var n=1
        for(row in 0..3) for(col in 0..row) { val x=(col-row/2f)*radius*2.15f; balls.add(Ball(x,-.38f-row*radius*1.85f,color=palette[(n-1)%7],number=n)); n++ }
    }
    private fun color(r:Float,g:Float,b:Float)=floatArrayOf(r,g,b,1f)
    override fun onSurfaceCreated(gl:GL10?, config:EGLConfig?){
        GLES20.glClearColor(.035f,.055f,.07f,1f); GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vs="attribute vec3 aPosition; uniform mat4 uMVP; void main(){ gl_Position=uMVP*vec4(aPosition,1.0); }"
        val fs="precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }"
        program=GLES20.glCreateProgram()
        val v=shader(GLES20.GL_VERTEX_SHADER,vs); val f=shader(GLES20.GL_FRAGMENT_SHADER,fs)
        GLES20.glAttachShader(program,v); GLES20.glAttachShader(program,f); GLES20.glLinkProgram(program)
        aPos=GLES20.glGetAttribLocation(program,"aPosition"); uMvp=GLES20.glGetUniformLocation(program,"uMVP"); uColor=GLES20.glGetUniformLocation(program,"uColor")
        buildSphere()
    }
    private fun shader(type:Int,src:String):Int { val s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s); return s }
    private fun buildSphere(){
        val data=ArrayList<Float>(); val lat=12; val lon=18
        for(i in 0 until lat) for(j in 0 until lon){
            val p1=-Math.PI/2+i*Math.PI/lat; val p2=-Math.PI/2+(i+1)*Math.PI/lat
            val t1=j*2*Math.PI/lon; val t2=(j+1)*2*Math.PI/lon
            fun v(p:Double,t:Double){data.add((cos(p)*cos(t)).toFloat());data.add(sin(p).toFloat());data.add((cos(p)*sin(t)).toFloat())}
            v(p1,t1);v(p2,t1);v(p2,t2);v(p1,t1);v(p2,t2);v(p1,t2)
        }
        sphereCount=data.size/3
        val bb=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()); sphere=bb.asFloatBuffer(); data.forEach{sphere.put(it)}; sphere.position(0)
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){ GLES20.glViewport(0,0,w,h); Matrix.perspectiveM(projection,0, if(firstPerson)52f else 43f,w.toFloat()/h.coerceAtLeast(1),.1f,20f) }
    override fun onDrawFrame(gl:GL10?){
        val now=System.nanoTime(); val dt=((now-last)/1e9).toFloat().coerceIn(0f,.035f); last=now
        physics(dt)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(view,0, if(firstPerson)0f else 0f,if(firstPerson)1.3f else 2.9f,if(firstPerson)2.3f else 3.8f, 0f,0f,0f, 0f,1f,0f)
        drawBox(0f,-.13f,0f,2.5f,.16f,4.5f,color(.035f,.28f,.19f))
        drawBox(0f,-.02f,0f,2.72f,.10f,4.72f,color(.24f,.13f,.075f))
        // Rails and six dark pockets
        for(x in listOf(-1.32f,1.32f)) for(z in listOf(-2.32f,2.32f)) drawBall(x,.02f,z,.14f,color(.008f,.012f,.014f))
        for(x in listOf(-1.32f,1.32f)) drawBall(x,.02f,0f,.12f,color(.008f,.012f,.014f))
        balls.forEach{drawBall(it.x,.095f,it.z,radius,it.color)}
        // Aim guide
        val cue=balls.first()
        drawBox(cue.x+sin(angle)*.5f,.01f,cue.z-cos(angle)*.5f,.012f,.012f,1f,color(.9f,.86f,.68f))
    }
    private fun physics(dt:Float){
        balls.forEach{ b-> b.x+=b.vx*dt; b.z+=b.vz*dt; b.vx*=friction.pow(dt*60); b.vz*=friction.pow(dt*60)
            if(abs(b.vx)+abs(b.vz)<.008f){b.vx=0f;b.vz=0f}
            if(b.x < -1.18f+radius){b.x=-1.18f+radius;b.vx=abs(b.vx)*.86f}
            if(b.x > 1.18f-radius){b.x=1.18f-radius;b.vx=-abs(b.vx)*.86f}
            if(b.z < -2.18f+radius){b.z=-2.18f+radius;b.vz=abs(b.vz)*.86f}
            if(b.z > 2.18f-radius){b.z=2.18f-radius;b.vz=-abs(b.vz)*.86f}
        }
        for(i in balls.indices) for(j in i+1 until balls.size){
            val a=balls[i];val b=balls[j];val dx=b.x-a.x;val dz=b.z-a.z;val d2=dx*dx+dz*dz;val minD=radius*2
            if(d2<minD*minD && d2>.000001f){
                val d=sqrt(d2);val nx=dx/d;val nz=dz/d;val overlap=(minD-d)/2
                a.x-=nx*overlap;a.z-=nz*overlap;b.x+=nx*overlap;b.z+=nz*overlap
                val rel=(a.vx-b.vx)*nx+(a.vz-b.vz)*nz
                if(rel>0){val impulse=rel*.94f;a.vx-=impulse*nx;a.vz-=impulse*nz;b.vx+=impulse*nx;b.vz+=impulse*nz}
            }
        }
        // Pocket capture
        balls.removeAll { b-> val pocket=(abs(b.x)>1.17f && abs(b.z)>2.15f)|| (abs(b.x)>1.17f && abs(b.z)<.12f); pocket && b.number!=0 }
    }
    private fun drawBall(x:Float,y:Float,z:Float,r:Float,c:FloatArray){ Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,r,r,r); draw(sphere,sphereCount,c) }
    private fun drawBox(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,c:FloatArray){
        val verts=floatArrayOf(-.5f,-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,-.5f,-.5f,.5f,-.5f,-.5f,-.5f,.5f,.5f,-.5f,.5f,.5f,.5f,.5f,-.5f,.5f,.5f)
        val bb=ByteBuffer.allocateDirect(verts.size*4).order(ByteOrder.nativeOrder());val fb=bb.asFloatBuffer();fb.put(verts);fb.position(0)
        Matrix.setIdentityM(model,0);Matrix.translateM(model,0,x,y,z);Matrix.scaleM(model,0,sx,sy,sz);draw(fb,8,c)
    }
    private fun draw(buf:FloatBuffer,count:Int,c:FloatArray){
        Matrix.multiplyMM(mvp,0,view,0,model,0);val out=FloatArray(16);Matrix.multiplyMM(out,0,projection,0,mvp,0)
        GLES20.glUseProgram(program);GLES20.glEnableVertexAttribArray(aPos);GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,0,buf)
        GLES20.glUniformMatrix4fv(uMvp,1,false,out,0);GLES20.glUniform4fv(uColor,1,c,0);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,count);GLES20.glDisableVertexAttribArray(aPos)
    }
}
