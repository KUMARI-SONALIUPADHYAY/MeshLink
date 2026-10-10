package com.meshlink.app.core.bluetooth
import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
class BleManager(private val context:Context,private val identityId:String,private val displayName:String){
    private val adapter get()=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner=BleScanner(context, { peer -> peers[peer.address]=peer; onPeerDiscovered?.invoke(peer) }, { status -> onStatusChanged?.invoke(status) })
    private val peers=ConcurrentHashMap<String,MeshPeer>()
    private var advertiser:BleAdvertiser?=null
    var onPeerDiscovered:((MeshPeer)->Unit)?=null
    var onStatusChanged:((String)->Unit)?=null
    fun start(){
        val a=adapter?:run{onStatusChanged?.invoke("Bluetooth unsupported");return}
        if(!a.isEnabled){onStatusChanged?.invoke("Bluetooth is OFF");return}
        if(!permissions()){onStatusChanged?.invoke("Bluetooth permissions required");return}
        advertiser=BleAdvertiser(a) { status -> onStatusChanged?.invoke(status) }.also{it.start(identityId,displayName)}
        scanner.start()
    }
    fun stop(){scanner.stop();advertiser?.stop()}
    fun snapshot()=peers.values.toList()
    private fun permissions()=if(Build.VERSION.SDK_INT>=31)
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED
    else ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
}
