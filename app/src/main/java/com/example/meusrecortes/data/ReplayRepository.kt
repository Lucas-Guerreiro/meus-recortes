package com.example.meusrecortes.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ClipInfo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)

class ReplayRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ReplayRepository"
        private const val PREFS_NAME = "meus_recortes_prefs"
        private const val KEY_ANTES_SEGUNDOS = "antes_segundos"
        private const val KEY_DEPOIS_SEGUNDOS = "depois_segundos"
        
        // Chaves de ativação local
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_IS_ACTIVATED = "is_activated"
        private const val KEY_LICENSE_KEY = "license_key"

        @Volatile
        private var INSTANCE: ReplayRepository? = null

        fun getInstance(context: Context): ReplayRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ReplayRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Canal de atualização para a lista de vídeos
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply {
        tryEmit(Unit)
    }

    // Configurações de tempo
    fun getAntesSegundos(): Int = sharedPrefs.getInt(KEY_ANTES_SEGUNDOS, 5)
    fun getDepoisSegundos(): Int = sharedPrefs.getInt(KEY_DEPOIS_SEGUNDOS, 5)

    fun saveConfig(antes: Int, depois: Int) {
        sharedPrefs.edit()
            .putInt(KEY_ANTES_SEGUNDOS, antes)
            .putInt(KEY_DEPOIS_SEGUNDOS, depois)
            .apply()
    }

    /**
     * Retorna o Device ID exclusivo deste aparelho.
     * Se não existir, gera um novo UUID curto e o armazena permanentemente.
     */
    fun getDeviceId(): String {
        var deviceId = sharedPrefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            // Gera um ID simples alfanumérico de 8 caracteres
            deviceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8).uppercase(Locale.ROOT)
            sharedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            Log.d(TAG, "Novo Device ID gerado: $deviceId")
        }
        return deviceId
    }

    /**
     * Verifica se o aplicativo está ativado localmente (offline-friendly).
     */
    fun isActivatedLocal(): Boolean {
        // Se as credenciais do Supabase não foram configuradas, libera por padrão para desenvolvimento
        if (SupabaseConfig.SUPABASE_URL == "SUA_SUPABASE_URL_AQUI") {
            return true
        }
        return sharedPrefs.getBoolean(KEY_IS_ACTIVATED, false)
    }

    fun getLocalLicenseKey(): String {
        return sharedPrefs.getString(KEY_LICENSE_KEY, "") ?: ""
    }

    private fun saveActivationLocal(licenseKey: String) {
        sharedPrefs.edit()
            .putBoolean(KEY_IS_ACTIVATED, true)
            .putString(KEY_LICENSE_KEY, licenseKey)
            .apply()
    }

    fun clearActivationLocal() {
        sharedPrefs.edit()
            .putBoolean(KEY_IS_ACTIVATED, false)
            .putString(KEY_LICENSE_KEY, "")
            .apply()
    }

    /**
     * Valida e vincula uma licença na tela de ativação via API REST nativa do Supabase.
     */
    suspend fun validateLicenseOnSupabase(licenseKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (SupabaseConfig.SUPABASE_URL == "SUA_SUPABASE_URL_AQUI") {
            saveActivationLocal(licenseKey)
            return@withContext Result.success(true)
        }

        val deviceId = getDeviceId()
        var connection: HttpURLConnection? = null
        try {
            val keyEncoded = Uri.encode(licenseKey.trim())
            val urlString = "${SupabaseConfig.SUPABASE_URL}/rest/v1/licenses?license_key=eq.$keyEncoded&select=*"
            val url = URL(urlString)
            
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Erro HTTP Supabase GET: Code $responseCode. $errorMsg")
                return@withContext Result.failure(Exception("Erro ao conectar ao servidor ($responseCode)"))
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            
            if (responseText.trim() == "[]") {
                return@withContext Result.failure(Exception("Chave de licença inválida ou inexistente."))
            }

            // Validar se a licença está ativa no banco
            val isActive = responseText.contains("\"is_active\":true")
            if (!isActive) {
                return@withContext Result.failure(Exception("Esta licença foi desativada pelo administrador."))
            }

            // Extrair o device_id registrado por regex
            val pattern = java.util.regex.Pattern.compile("\"device_id\":\"([^\"]+)\"")
            val matcher = pattern.matcher(responseText)
            val registeredDeviceId = if (matcher.find()) matcher.group(1) else null

            if (registeredDeviceId != null && registeredDeviceId != "null" && registeredDeviceId != deviceId) {
                return@withContext Result.failure(Exception("Esta licença já está em uso em outro aparelho."))
            }

            // Se o device_id estiver livre (nulo), realiza o registro no banco via PATCH
            if (registeredDeviceId == null || registeredDeviceId == "null") {
                val patchResult = associateDeviceOnSupabase(licenseKey.trim(), deviceId)
                if (patchResult.isFailure) {
                    return@withContext Result.failure(patchResult.exceptionOrNull() ?: Exception("Falha ao registrar aparelho."))
                }
            }

            // Licença ativa e vinculada! Gravar localmente
            saveActivationLocal(licenseKey.trim())
            return@withContext Result.success(true)

        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao validar licença no Supabase: ", e)
            return@withContext Result.failure(Exception("Sem conexão com o servidor. Verifique a internet e tente novamente."))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Vincula o Device ID deste dispositivo à linha da licença no Supabase.
     */
    private fun associateDeviceOnSupabase(licenseKey: String, deviceId: String): Result<Unit> {
        var connection: HttpURLConnection? = null
        try {
            val keyEncoded = Uri.encode(licenseKey)
            val urlString = "${SupabaseConfig.SUPABASE_URL}/rest/v1/licenses?license_key=eq.$keyEncoded"
            val url = URL(urlString)
            
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH"
            connection.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            val isoDate = sdf.format(Date())
            
            val jsonBody = "{\"device_id\":\"$deviceId\",\"activated_at\":\"$isoDate\"}"

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200 || responseCode == 204) {
                return Result.success(Unit)
            } else {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Erro HTTP Supabase PATCH: Code $responseCode. $errorMsg")
                return Result.failure(Exception("Falha ao vincular dispositivo."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao fazer PATCH no Supabase: ", e)
            return Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Checagem assíncrona ultra-estrita de estado da licença no Supabase.
     * Utiliza parser de JSON nativo. Se a chave foi editada, excluída, se is_active virou false
     * ou se o device_id no banco mudou, revoga a licença no celular IMEDIATAMENTE.
     */
    fun checkLicenseStatusOnStartup(): Flow<Boolean> = flow {
        if (!isActivatedLocal()) {
            emit(false)
            return@flow
        }

        if (SupabaseConfig.SUPABASE_URL == "SUA_SUPABASE_URL_AQUI") {
            emit(true)
            return@flow
        }

        val key = getLocalLicenseKey()
        if (key.isEmpty()) {
            clearActivationLocal()
            emit(false)
            return@flow
        }

        val deviceId = getDeviceId()
        var connection: HttpURLConnection? = null

        try {
            val keyEncoded = Uri.encode(key.trim())
            val urlString = "${SupabaseConfig.SUPABASE_URL}/rest/v1/licenses?license_key=eq.$keyEncoded&select=*"
            val url = URL(urlString)
            
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            connection.connectTimeout = 6000
            connection.readTimeout = 6000

            val responseCode = connection.responseCode
            Log.d(TAG, "Checagem remota HTTP Status: $responseCode")

            if (responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Resposta do Supabase: $responseText")
                
                val jsonArray = org.json.JSONArray(responseText)

                // 1. Se a chave foi excluída ou o nome foi alterado no banco (array vazio [])
                if (jsonArray.length() == 0) {
                    Log.w(TAG, "REVOGAÇÃO: Licença excluída ou alterada no banco Supabase!")
                    clearActivationLocal()
                    emit(false)
                    return@flow
                }

                val item = jsonArray.getJSONObject(0)
                val isActive = item.optBoolean("is_active", false)
                val registeredDeviceId = if (item.isNull("device_id") || item.optString("device_id").isEmpty()) null else item.optString("device_id")

                // 2. Se is_active for false
                if (!isActive) {
                    Log.w(TAG, "REVOGAÇÃO: Licença desativada no banco (is_active = false)!")
                    clearActivationLocal()
                    emit(false)
                    return@flow
                }

                // 3. Se o device_id registrado for diferente do ID deste aparelho
                if (registeredDeviceId == null || registeredDeviceId != deviceId) {
                    Log.w(TAG, "REVOGAÇÃO: Device ID no banco ($registeredDeviceId) é diferente deste celular ($deviceId)!")
                    clearActivationLocal()
                    emit(false)
                    return@flow
                }

                // Passou em todos os critérios de validação estritos
                Log.d(TAG, "LICENÇA VÁLIDA E CONFIRMADA PELO BANCO!")
                emit(true)

            } else if (responseCode in 400..499) {
                Log.w(TAG, "Erro HTTP $responseCode no Supabase. Revogando licença.")
                clearActivationLocal()
                emit(false)
            } else {
                // Indisponibilidade de servidor (500) -> tolera offline
                emit(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção na verificação remota: ${e.javaClass.simpleName} - ${e.message}")
            emit(true)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Notifica repositório de alteração nos arquivos
     */
    fun notifyNewClipSaved() {
        refreshTrigger.tryEmit(Unit)
    }

    val savedClipsFlow: Flow<List<ClipInfo>> = flow {
        refreshTrigger.collect {
            emit(querySavedClips())
        }
    }

    private fun querySavedClips(): List<ClipInfo> {
        val clips = ArrayList<ClipInfo>()
        
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        }

        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%Movies/Meus Recortes%")
        } else {
            arrayOf("REPLAY_%")
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        try {
            context.contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val dateAdded = cursor.getLong(dateColumn)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    clips.add(
                        ClipInfo(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            durationMs = duration,
                            sizeBytes = size,
                            dateAddedSec = dateAdded
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar MediaStore: ", e)
        }

        return clips
    }

    fun deleteClip(clip: ClipInfo): Boolean {
        return try {
            val rowsDeleted = context.contentResolver.delete(clip.uri, null, null)
            if (rowsDeleted > 0) {
                notifyNewClipSaved()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao excluir arquivo de vídeo: ", e)
            false
        }
    }

    /**
     * Envia um comando remoto para acionar o corte de replay à distância.
     */
    suspend fun sendRemoteClipCommand(licenseKey: String): Boolean = withContext(Dispatchers.IO) {
        if (SupabaseConfig.SUPABASE_URL == "SUA_SUPABASE_URL_AQUI" || licenseKey.isEmpty()) {
            return@withContext false
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL("${SupabaseConfig.SUPABASE_URL}/rest/v1/replay_commands")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=minimal")
            connection.doOutput = true
            connection.connectTimeout = 5000

            val jsonBody = "{\"license_key\":\"${licenseKey.trim()}\",\"command\":\"TRIGGER_CLIP\"}"

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray())
                os.flush()
            }

            val code = connection.responseCode
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar comando remoto: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Verifica se houve algum novo comando de corte remoto enviado pelo outro dispositivo usando o ID do comando.
     * Retorna o ID do novo comando se houver, ou null se não houver.
     */
    suspend fun checkRemoteClipCommandId(licenseKey: String, lastProcessedId: String): String? = withContext(Dispatchers.IO) {
        if (SupabaseConfig.SUPABASE_URL == "SUA_SUPABASE_URL_AQUI" || licenseKey.isEmpty()) {
            return@withContext null
        }

        var connection: HttpURLConnection? = null
        try {
            val keyEncoded = Uri.encode(licenseKey.trim())
            val urlString = "${SupabaseConfig.SUPABASE_URL}/rest/v1/replay_commands?license_key=eq.$keyEncoded&order=created_at.desc&limit=1"
            val url = URL(urlString)

            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = org.json.JSONArray(responseText)
                if (jsonArray.length() > 0) {
                    val item = jsonArray.getJSONObject(0)
                    val commandId = item.optString("id", "")
                    
                    if (commandId.isNotEmpty() && commandId != lastProcessedId) {
                        return@withContext commandId
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
