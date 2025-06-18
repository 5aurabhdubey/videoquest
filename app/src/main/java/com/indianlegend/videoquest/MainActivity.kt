package com.indianlegend.videoquest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.indianlegend.videoquest.BuildConfig
import okhttp3.RequestBody
import java.io.InputStream
import java.security.MessageDigest

// Data Models
data class Task(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val points: Int = 0,
    val durationSeconds: Int = 30
)

data class User(
    val id: String = "",
    val email: String = "",
    val role: String = "",
    val points: Int = 0
)

data class VideoMetadata(
    val id: String = "",
    val videourl: String = "", // Stores B2 fileName (e.g., "userId-taskId-timestamp.mp4")
    val taskId: String = "",
    val userId: String = "",
    val submittedAt: Long = 0,
    val status: String = "pending",
    val completionNote: String = "",
    val uploadFailed: Boolean = false
)

class TaskViewModel : ViewModel() {
    private val firestore = FirebaseFirestore.getInstance()
    private val client = OkHttpClient()
    private val bucketId = "cb6c247adda56ffd9d7e0b1b"
    private val bucketName = "videoquest-app-videos-2025"
    private var authToken: String? = null
    private var apiUrl: String? = null
    private var uploadUrl: String? = null
    private var uploadAuthToken: String? = null

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _recordedVideos = MutableStateFlow<List<VideoMetadata>>(emptyList())
    val recordedVideos: StateFlow<List<VideoMetadata>> = _recordedVideos.asStateFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val auth = FirebaseAuth.getInstance()

    init {
        refreshData()
    }

    fun setUser(user: User) {
        _user.value = user
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshData() {
        viewModelScope.launch {
            var attempt = 0
            val MAX_ATTEMPTS = 3
            while (attempt < MAX_ATTEMPTS) {
                try {
                    val tasksSnapshot = firestore.collection("tasks").get().await()
                    _tasks.value = tasksSnapshot.documents.mapNotNull { doc ->
                        doc.toObject(Task::class.java)?.copy(id = doc.id)
                    }
                    val videosSnapshot = firestore.collection("videos").get().await()
                    _recordedVideos.value = videosSnapshot.documents.mapNotNull { doc ->
                        doc.toObject(VideoMetadata::class.java)?.copy(id = doc.id)
                    }
                    auth.currentUser?.uid?.let { userId ->
                        val userDoc = firestore.collection("users").document(userId).get().await()
                        if (userDoc.exists()) {
                            _user.value = userDoc.toObject(User::class.java)?.copy(id = userId)
                        } else {
                            val newUser = User(
                                id = userId,
                                email = auth.currentUser?.email ?: "",
                                role = if (auth.currentUser?.email == "admin@example.com") "admin" else "user",
                                points = 50
                            )
                            firestore.collection("users").document(userId).set(newUser).await()
                            _user.value = newUser
                        }
                    }
                    _errorMessage.value = null
                    break
                } catch (e: FirebaseFirestoreException) {
                    attempt++
                    when (e.code) {
                        FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                            _errorMessage.value = "Failed to load data: PERMISSION_DENIED: Check Firestore rules."
                            break
                        }
                        FirebaseFirestoreException.Code.UNAVAILABLE -> {
                            _errorMessage.value = "Network error, retrying... (Attempt $attempt/$MAX_ATTEMPTS)"
                            delay(2000L * attempt)
                            if (attempt == MAX_ATTEMPTS) {
                                _errorMessage.value = "Failed to load data: Network unavailable after $MAX_ATTEMPTS attempts."
                            }
                        }
                        else -> {
                            _errorMessage.value = "Failed to load data: ${e.message}"
                            break
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to load data: ${e.message}"
                    break
                }
            }
        }
    }

    fun clearData() {
        _tasks.value = emptyList()
        _recordedVideos.value = emptyList()
        _user.value = null
        _errorMessage.value = null
    }

    suspend fun addTask(task: Task) {
        try {
            firestore.collection("tasks").document(task.id).set(task).await()
            _tasks.value = _tasks.value + task
        } catch (e: Exception) {
            _errorMessage.value = "Failed to add task: ${e.message}"
            throw e
        }
    }

    suspend fun deleteTask(task: Task) {
        try {
            firestore.collection("tasks").document(task.id).delete().await()
            _tasks.value = _tasks.value - task
        } catch (e: Exception) {
            _errorMessage.value = "Failed to delete task: ${e.message}"
            throw e
        }
    }

    private suspend fun authenticate() = withContext(Dispatchers.IO) {
        val credentials = Base64.encodeToString(
            "${BuildConfig.B2_KEY_ID}:${BuildConfig.B2_APPLICATION_KEY}".toByteArray(),
            Base64.NO_WRAP
        )
        val request = Request.Builder()
            .url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
            .header("Authorization", "Basic $credentials")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                authToken = json.getString("authorizationToken")
                apiUrl = json.getString("apiUrl")
                uploadUrl = null
            } else {
                throw Exception("Authentication failed: ${response.message}")
            }
        }
    }

    private suspend fun getUploadUrl(): String = withContext(Dispatchers.IO) {
        if (authToken == null || apiUrl == null) authenticate()
        val requestBody = JSONObject().put("bucketId", bucketId).toString()
        val request = Request.Builder()
            .url("$apiUrl/b2api/v2/b2_get_upload_url")
            .header("Authorization", authToken!!)
            .post(RequestBody.create("application/json".toMediaType(), requestBody))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                uploadAuthToken = json.getString("authorizationToken")
                json.getString("uploadUrl")
            } else {
                throw Exception("Failed to get upload URL: ${response.message}")
            }
        }
    }

    suspend fun uploadVideo(
        videoPath: String,
        taskId: String,
        userId: String = "anonymous", // Default to anonymous if no user
        completionNote: String
    ): String? {
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            _errorMessage.value = "Video file not found"
            return null
        }

        val fileName = "$userId-$taskId-${System.currentTimeMillis()}.mp4"
        val sha1 = videoFile.inputStream().use { it.digestSha1() }
        var retryCount = 0
        val maxRetries = 3
        var result: String? = null

        while (retryCount < maxRetries && result == null) {
            try {
                val uploadUrl = getUploadUrl()
                val request = Request.Builder()
                    .url(uploadUrl)
                    .header("Authorization", uploadAuthToken!!)
                    .header("X-Bz-File-Name", fileName)
                    .header("Content-Type", "video/mp4")
                    .header("X-Bz-Content-Sha1", sha1)
                    .header("X-Bz-Info-b2-content-disposition", "attachment; filename=\"$fileName\"")
                    .post(videoFile.asRequestBody("video/mp4".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val videoId = fileName
                        val videoMetadata = VideoMetadata(
                            id = "",
                            videourl = fileName,
                            taskId = taskId,
                            userId = userId,
                            submittedAt = System.currentTimeMillis(),
                            status = "pending",
                            completionNote = completionNote,
                            uploadFailed = false
                        )
                        val docRef = firestore.collection("videos").add(videoMetadata).await()
                        _recordedVideos.value = _recordedVideos.value + videoMetadata.copy(id = docRef.id)

                        if (videoFile.exists()) videoFile.delete()

                        result = docRef.id
                    } else {
                        _errorMessage.value = "Upload failed: ${response.message}"
                    }
                }
                if (result == null && retryCount < maxRetries - 1) {
                    retryCount++
                    delay(1000L * retryCount)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Upload failed: ${e.message}"
                if (result == null && retryCount < maxRetries - 1) {
                    retryCount++
                    delay(1000L * retryCount)
                }
            }
        }
        return result
    }

    suspend fun getVideoDownloadUrl(fileName: String): String? = withContext(Dispatchers.IO) {
        if (authToken == null || apiUrl == null) authenticate()
        val request = Request.Builder()
            .url("$apiUrl/b2api/v2/b2_get_download_authorization")
            .header("Authorization", authToken!!)
            .post(
                RequestBody.create(
                    "application/json".toMediaType(),
                    JSONObject()
                        .put("bucketId", bucketId)
                        .put("fileNamePrefix", fileName)
                        .put("validDurationInSeconds", 3600)
                        .toString()
                )
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val downloadToken = json.getString("authorizationToken")
                "https://f005.backblazeb2.com/file/$bucketName/$fileName?Authorization=$downloadToken"
            } else {
                _errorMessage.value = "Failed to generate video URL: ${response.message}"
                null
            }
        }
    }

    suspend fun deleteVideo(video: VideoMetadata) = withContext(Dispatchers.IO) {
        if (authToken == null || apiUrl == null) authenticate()
        val request = Request.Builder()
            .url("$apiUrl/b2api/v2/b2_delete_file_version")
            .header("Authorization", authToken!!)
            .post(
                RequestBody.create(
                    "application/json".toMediaType(),
                    JSONObject()
                        .put("fileName", video.videourl)
                        .put("fileId", video.videourl)
                        .toString()
                )
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                firestore.collection("videos").document(video.id).delete().await()
                _recordedVideos.value = _recordedVideos.value.filter { it.id != video.id }
                true
            } else {
                _errorMessage.value = "Failed to delete video: ${response.message}"
                false
            }
        }
    }

    private fun InputStream.digestSha1(): String {
        val digest = MessageDigest.getInstance("SHA1")
        val buffer = ByteArray(8192)
        var read: Int
        while (read(buffer).also { read = it } > 0) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun approveVideo(videoId: String, isApproved: Boolean) {
        try {
            firestore.collection("videos").document(videoId)
                .update("status", if (isApproved) "approved" else "pending").await()
            _recordedVideos.value = _recordedVideos.value.map {
                if (it.id == videoId) it.copy(status = if (isApproved) "approved" else "pending") else it
            }
            if (isApproved) {
                val video = _recordedVideos.value.find { it.id == videoId }
                video?.let {
                    val taskPoints = getTaskPoints(it.taskId)
                    updateUserPoints(it.userId, taskPoints)
                }
            }
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update video approval: ${e.message}"
            throw e
        }
    }

    private suspend fun updateUserPoints(userId: String, pointsToAdd: Int) {
        try {
            val userRef = firestore.collection("users").document(userId)
            firestore.runTransaction { transaction ->
                val userSnapshot = transaction.get(userRef)
                val currentPoints = userSnapshot.getLong("points")?.toInt() ?: 0
                transaction.update(userRef, "points", currentPoints + pointsToAdd)
            }.await()
            refreshData()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update user points: ${e.message}"
            throw e
        }
    }

    fun getTaskPoints(taskId: String): Int {
        return tasks.value.find { it.id == taskId }?.points ?: 0
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var analytics: FirebaseAnalytics
    private lateinit var firestore: FirebaseFirestore

    private val taskViewModel: TaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth
        analytics = Firebase.analytics
        firestore = Firebase.firestore

        lifecycleScope.launch {
            val tasksSnapshot = firestore.collection("tasks").get().await()
            if (tasksSnapshot.isEmpty) {
                firestore.collection("tasks").document("task1").set(
                    Task("task1", "Task 1", "Complete this task", 10, 30)
                ).await()
                firestore.collection("tasks").document("task2").set(
                    Task("task2", "Task 2", "Record a video", 20, 60)
                ).await()
            }
        }

        setContent {
            VideoQuestTheme {
                VideoQuestApp(auth, analytics, taskViewModel)
            }
        }
    }
}
@Composable
fun VideoQuestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3F51B5),
            secondary = Color(0xFFFF4081),
            background = Color(0xFFECEFF1),
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black
        ),
        typography = Typography(),
        content = content
    )
}

@Composable
fun VideoQuestApp(auth: FirebaseAuth, analytics: FirebaseAnalytics, viewModel: TaskViewModel) {
    val navController = rememberNavController()
    var currentUser by remember { mutableStateOf<User?>(null) }
    val firestore = Firebase.firestore

    LaunchedEffect(auth.currentUser) {
        auth.currentUser?.let { firebaseUser ->
            val userDoc = firestore.collection("users").document(firebaseUser.uid).get().await()
            val user = if (userDoc.exists()) {
                userDoc.toObject(User::class.java)
            } else {
                val newUser = User(
                    id = firebaseUser.uid,
                    email = firebaseUser.email ?: "",
                    role = if (firebaseUser.email == "admin@example.com") "admin" else "user",
                    points = 50
                )
                firestore.collection("users").document(firebaseUser.uid).set(newUser).await()
                newUser
            }
            currentUser = user
            viewModel.setUser(user!!)
            viewModel.refreshData()
        } ?: run {
            currentUser = null
            viewModel.clearData()
        }
    }

    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                auth = auth,
                analytics = analytics,
                onLoginSuccess = { role ->
                    val user = User(
                        id = auth.currentUser?.uid ?: "",
                        email = auth.currentUser?.email ?: "",
                        role = role,
                        points = 50
                    )
                    viewModel.setUser(user)
                    currentUser = user
                    analytics.logEvent(FirebaseAnalytics.Event.LOGIN) {
                        param(FirebaseAnalytics.Param.METHOD, "email")
                        param("role", role)
                    }
                    if (role == "admin") {
                        navController.navigate("admin") {
                            popUpTo("login") { inclusive = true }
                        }
                    } else {
                        navController.navigate("task") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate("register")
                }
            )
        }
        composable("register") {
            RegisterScreen(
                auth = auth,
                analytics = analytics,
                onRegisterSuccess = {
                    navController.navigate("login") {
                        popUpTo("register") { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate("login") {
                        popUpTo("register") { inclusive = true }
                    }
                }
            )
        }
        composable("task") {
            TaskScreen(
                user = currentUser ?: User(),
                viewModel = viewModel,
                onNavigateToVideoRecord = { taskId ->
                    navController.navigate("video_record/$taskId")
                },
                onNavigateToPoints = {
                    navController.navigate("points")
                },
                onNavigateToPlayback = { fileName ->
                    val encodedFileName = Uri.encode(fileName)
                    navController.navigate("playback/$encodedFileName")
                },
                onLogout = {
                    auth.signOut()
                    currentUser = null
                    viewModel.clearData()
                    analytics.logEvent("logout") {
                        param("user_type", "user")
                    }
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable("admin") {
            AdminScreen(
                user = currentUser ?: User(),
                viewModel = viewModel,
                onNavigateToPoints = {
                    navController.navigate("points")
                },
                onNavigateToPlayback = { fileName ->
                    val encodedFileName = Uri.encode(fileName)
                    navController.navigate("playback/$encodedFileName")
                },
                onLogout = {
                    auth.signOut()
                    currentUser = null
                    viewModel.clearData()
                    analytics.logEvent("logout") {
                        param("user_type", "admin")
                    }
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onUserPointsUpdate = { updatedPoints ->
                    currentUser = currentUser?.copy(points = updatedPoints)
                    viewModel.setUser(currentUser!!)
                }
            )
        }
        composable("points") {
            PointsScreen(
                user = currentUser ?: User(),
                recordedVideos = viewModel.recordedVideos,
                onNavigateToPlayback = { fileName ->
                    val encodedFileName = Uri.encode(fileName)
                    navController.navigate("playback/$encodedFileName")
                },
                onBack = {
                    if (currentUser?.role == "admin") {
                        navController.navigate("admin") {
                            popUpTo("points") { inclusive = true }
                        }
                    } else {
                        navController.navigate("task") {
                            popUpTo("points") { inclusive = true }
                        }
                    }
                },
                viewModel = viewModel
            )
        }
        composable("video_record/{taskId}") { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
            VideoRecordScreen(
                taskId = taskId,
                viewModel = viewModel,
                analytics = analytics,
                userId = currentUser?.id ?: "",
                onBack = {
                    navController.navigate("task") {
                        popUpTo("video_record/$taskId") { inclusive = true }
                    }
                },
                onNavigateToCompletionNote = { videoPath ->
                    val encodedVideoPath = Uri.encode(videoPath)
                    navController.navigate("completion_note/$taskId/$encodedVideoPath")
                }
            )
        }
        composable("completion_note/{taskId}/{videoPath}") { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
            val encodedVideoPath = backStackEntry.arguments?.getString("videoPath") ?: ""
            val videoPath = Uri.decode(encodedVideoPath)
            CompletionNoteScreen(
                taskId = taskId,
                videoPath = videoPath,
                viewModel = viewModel,
                analytics = analytics,
                userId = currentUser?.id ?: "",
                onBack = {
                    navController.navigate("task") {
                        popUpTo("completion_note/$taskId/$encodedVideoPath") { inclusive = true }
                    }
                }
            )
        }
        composable("playback/{fileName}") { backStackEntry ->
            val encodedFileName = backStackEntry.arguments?.getString("fileName") ?: ""
            val fileName = Uri.decode(encodedFileName)
            VideoPlaybackScreen(
                fileName = fileName,
                viewModel = viewModel,
                analytics = analytics,
                onBack = {
                    if (currentUser?.role == "admin") {
                        navController.navigate("admin") {
                            popUpTo("playback/$encodedFileName") { inclusive = true }
                        }
                    } else {
                        navController.navigate("task") {
                            popUpTo("playback/$encodedFileName") { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun LoginScreen(
    auth: FirebaseAuth,
    analytics: FirebaseAnalytics,
    onLoginSuccess: (String) -> Unit,
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var triggerLogin by remember { mutableStateOf(false) }
    val firestore = Firebase.firestore
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(triggerLogin) {
        if (triggerLogin) {
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    val user = auth.currentUser!!
                    val role = if (user.email == "admin@example.com") "admin" else "user"
                    coroutineScope.launch(Dispatchers.IO) {
                        val userDoc = firestore.collection("users").document(user.uid).get().await()
                        if (!userDoc.exists()) {
                            firestore.collection("users").document(user.uid).set(
                                User(
                                    id = user.uid,
                                    email = user.email ?: "",
                                    role = role,
                                    points = 50
                                )
                            ).await()
                        }
                        withContext(Dispatchers.Main) {
                            isLoading = false
                            onLoginSuccess(role)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    isLoading = false
                    errorMessage = e.message ?: "Login failed"
                    analytics.logEvent("login_error") {
                        param("error_message", errorMessage!!)
                        param("email", email)
                    }
                    triggerLogin = false
                }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF3F51B5), Color(0xFFFF4081))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome to VideoQuest",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                modifier = Modifier.semantics { contentDescription = "App title" }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Email input field" },
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color(0xFF3F51B5),
                            focusedLabelColor = Color(0xFF3F51B5),
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Password input field" },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color(0xFF3F51B5),
                            focusedLabelColor = Color(0xFF3F51B5),
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (errorMessage != null) {
                        Text(
                            errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { contentDescription = "Error message: $errorMessage" }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                errorMessage = "Email and password cannot be empty"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            triggerLogin = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics { contentDescription = "Login button" },
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F51B5),
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Text("Login", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onNavigateToRegister,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics { contentDescription = "Register button" },
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF4081),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Register", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    auth: FirebaseAuth,
    analytics: FirebaseAnalytics,
    onRegisterSuccess: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var triggerRegister by remember { mutableStateOf(false) }
    val firestore = Firebase.firestore
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(triggerRegister) {
        if (triggerRegister) {
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    val user = auth.currentUser!!
                    coroutineScope.launch(Dispatchers.IO) {
                        firestore.collection("users").document(user.uid).set(
                            User(
                                id = user.uid,
                                email = user.email ?: "",
                                role = if (email == "admin@example.com") "admin" else "user",
                                points = 50
                            )
                        ).await()
                        withContext(Dispatchers.Main) {
                            isLoading = false
                            analytics.logEvent(FirebaseAnalytics.Event.SIGN_UP) {
                                param(FirebaseAnalytics.Param.METHOD, "email")
                            }
                            Toast.makeText(context, "Registration successful", Toast.LENGTH_SHORT).show()
                            onRegisterSuccess()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    isLoading = false
                    errorMessage = e.message ?: "Registration failed"
                    analytics.logEvent("register_error") {
                        param("error_message", errorMessage!!)
                        param("email", email)
                    }
                    triggerRegister = false
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Register", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Email input field" },
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color(0xFF3F51B5),
                            focusedLabelColor = Color(0xFF3F51B5),
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Password input field" },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color(0xFF3F51B5),
                            focusedLabelColor = Color(0xFF3F51B5),
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Confirm password input field" },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !isLoading,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color(0xFF3F51B5),
                            focusedLabelColor = Color(0xFF3F51B5),
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (errorMessage != null) {
                        Text(
                            errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { contentDescription = "Error message: $errorMessage" }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank() || confirmPassword.isBlank()) {
                                errorMessage = "All fields are required"
                                return@Button
                            }
                            if (password != confirmPassword) {
                                errorMessage = "Passwords do not match"
                                return@Button
                            }
                            if (password.length < 6) {
                                errorMessage = "Password must be at least 6 characters"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            triggerRegister = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics { contentDescription = "Register button" },
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F51B5),
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Text("Create Account", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics { contentDescription = "Back button" },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF4081),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back to Login", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    user: User,
    viewModel: TaskViewModel,
    onNavigateToVideoRecord: (String) -> Unit,
    onNavigateToPoints: () -> Unit,
    onNavigateToPlayback: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.tasks.collectAsState()
    val recordedVideos by viewModel.recordedVideos.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val filteredVideos = recordedVideos.filter { it.userId == user.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks - ${user.email}", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .semantics { contentDescription = "Error message: $errorMessage" }
                )
                Button(
                    onClick = { viewModel.clearError() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Dismiss error button" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        contentColor = Color.White
                    )
                ) {
                    Text("Dismiss Error")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                "Available Tasks",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3F51B5)
                ),
                modifier = Modifier.semantics { contentDescription = "Tasks section title" }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (tasks.isEmpty()) {
                Text(
                    "No tasks available.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "No tasks message" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(tasks) { task ->
                        TaskCard(
                            task = task,
                            onClickListener = { onNavigateToVideoRecord(task.id) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Recorded Videos",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3F51B5)
                ),
                modifier = Modifier.semantics { contentDescription = "Videos section title" }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredVideos.isEmpty()) {
                Text(
                    "No videos recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "No videos message" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(filteredVideos) { video ->
                        VideoItem(
                            videourl = video.videourl,
                            onPlayClick = { onNavigateToPlayback(video.videourl) },
                            status = video.status,
                            completionNote = video.completionNote,
                            viewModel = viewModel
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onNavigateToPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = "View points button" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4081),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View Points", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = "Logout button" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF5350),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    user: User,
    viewModel: TaskViewModel,
    onNavigateToPoints: () -> Unit,
    onNavigateToPlayback: (String) -> Unit,
    onLogout: () -> Unit,
    onUserPointsUpdate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var showDeleteTaskDialog by remember { mutableStateOf<Task?>(null) }
    var showDeleteVideoDialog by remember { mutableStateOf<VideoMetadata?>(null) }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDescription by remember { mutableStateOf("") }
    var newTaskPoints by remember { mutableStateOf("") }
    var newTaskDurationSeconds by remember { mutableStateOf("30") }
    val tasks by viewModel.tasks.collectAsState()
    val recordedVideos by viewModel.recordedVideos.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val analytics = Firebase.analytics
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Dashboard", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddTaskDialog = true },
                containerColor = Color(0xFFFF4081),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.semantics { contentDescription = "Add task button" }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add new task"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .semantics { contentDescription = "Error message: $errorMessage" }
                )
                Button(
                    onClick = { viewModel.clearError() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Dismiss error button" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        contentColor = Color.White
                    )
                ) {
                    Text("Dismiss Error")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                "Manage Tasks",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3F51B5)
                ),
                modifier = Modifier.semantics { contentDescription = "Manage tasks section title" }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (tasks.isEmpty()) {
                Text(
                    "No tasks available.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "No tasks message" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(tasks) { task ->
                        TaskCard(
                            task = task,
                            isAdmin = true,
                            onDeleteClickListener = {
                                showDeleteTaskDialog = task
                                analytics.logEvent("task_delete_initiated") {
                                    param("task_id", task.id)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Stored Videos",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3F51B5)
                ),
                modifier = Modifier.semantics { contentDescription = "Videos section title" }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (recordedVideos.isEmpty()) {
                Text(
                    "No videos stored yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "No videos message" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(recordedVideos) { video ->
                        VideoItem(
                            videourl = video.videourl,
                            onPlayClick = { onNavigateToPlayback(video.videourl) },
                            isAdmin = true,
                            status = video.status,
                            completionNote = video.completionNote,
                            viewModel = viewModel,
                            onApproveClick = {
                                viewModel.viewModelScope.launch {
                                    val wasApproved = video.status == "approved"
                                    viewModel.approveVideo(video.id, !wasApproved)
                                    if (!wasApproved) {
                                        val pointsToAdd = viewModel.getTaskPoints(video.taskId)
                                        onUserPointsUpdate(user.points + pointsToAdd)
                                    }
                                    analytics.logEvent("video_approval_toggled") {
                                        param("video_id", video.id)
                                        param("approved", (!wasApproved).toString())
                                    }
                                }
                            },
                            onShareClick = { url ->
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
                                analytics.logEvent("video_shared") {
                                    param("video_id", video.id)
                                }
                            },
                            onDeleteClick = {
                                showDeleteVideoDialog = video
                                analytics.logEvent("video_delete_initiated") {
                                    param("video_id", video.id)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onNavigateToPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = "View points button" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4081),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View Points", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = "Logout button" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF5350),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showAddTaskDialog) {
        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            title = { Text("Add New Task") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTaskTitle,
                        onValueChange = { newTaskTitle = it },
                        label = { Text("Task Title") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Task title input" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newTaskDescription,
                        onValueChange = { newTaskDescription = it },
                        label = { Text("Description") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Task description input" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newTaskPoints,
                        onValueChange = { newTaskPoints = it },
                        label = { Text("Points") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Task points input" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newTaskDurationSeconds,
                        onValueChange = { newTaskDurationSeconds = it },
                        label = { Text("Duration (seconds)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Task duration input" }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val points = newTaskPoints.toIntOrNull() ?: 0
                        val duration = newTaskDurationSeconds.toIntOrNull() ?: 30
                        if (newTaskTitle.isNotBlank() && points > 0 && duration > 0) {
                            viewModel.viewModelScope.launch {
                                viewModel.addTask(
                                    Task(
                                        id = "task${System.currentTimeMillis()}",
                                        title = newTaskTitle,
                                        description = newTaskDescription,
                                        points = points,
                                        durationSeconds = duration
                                    )
                                )
                                analytics.logEvent("task_added") {
                                    param("task_id", "task${System.currentTimeMillis()}")
                                }
                            }
                            newTaskTitle = ""
                            newTaskDescription = ""
                            newTaskPoints = ""
                            newTaskDurationSeconds = "30"
                            showAddTaskDialog = false
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Add task confirm button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F51B5)
                    )
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showAddTaskDialog = false },
                    modifier = Modifier.semantics { contentDescription = "Add task cancel button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteTaskDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteTaskDialog = null },
            title = { Text("Delete Task") },
            text = { Text("Are you sure you want to delete ${showDeleteTaskDialog!!.title}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.viewModelScope.launch {
                            viewModel.deleteTask(showDeleteTaskDialog!!)
                            analytics.logEvent("task_deleted") {
                                param("task_id", showDeleteTaskDialog!!.id)
                            }
                        }
                        showDeleteTaskDialog = null
                    },
                    modifier = Modifier.semantics { contentDescription = "Delete task confirm button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350)
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteTaskDialog = null },
                    modifier = Modifier.semantics { contentDescription = "Delete task cancel button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F51B5)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteVideoDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteVideoDialog = null },
            title = { Text("Delete Video") },
            text = { Text("Are you sure you want to delete this video?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.viewModelScope.launch {
                            viewModel.deleteVideo(showDeleteVideoDialog!!)
                            analytics.logEvent("video_deleted") {
                                param("video_id", showDeleteVideoDialog!!.id)
                            }
                        }
                        showDeleteVideoDialog = null
                    },
                    modifier = Modifier.semantics { contentDescription = "Delete video confirm button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350)
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteVideoDialog = null },
                    modifier = Modifier.semantics { contentDescription = "Delete video cancel button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F51B5)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsScreen(
    user: User,
    recordedVideos: StateFlow<List<VideoMetadata>>,
    onNavigateToPlayback: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TaskViewModel,
    modifier: Modifier = Modifier
) {
    val videos by recordedVideos.collectAsState()
    val filteredVideos = videos.filter { it.userId == user.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Points", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF3F51B5), Color(0xFFFF4081))
                            )
                        )
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = "User profile icon",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "User Profile",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier.semantics { contentDescription = "User profile title" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Email: ${user.email}",
                        style = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        modifier = Modifier.semantics { contentDescription = "User email: ${user.email}" }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Role: ${user.role}",
                        style = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        modifier = Modifier.semantics { contentDescription = "User role: ${user.role}" }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Points: ${user.points}",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier.semantics { contentDescription = "User points: ${user.points}" }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Recorded Videos",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3F51B5)
                ),
                modifier = Modifier.semantics { contentDescription = "Videos section title" }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredVideos.isEmpty()) {
                Text(
                    "No videos recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "No videos message" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(filteredVideos) { video ->
                        VideoItem(
                            videourl = video.videourl,
                            onPlayClick = { onNavigateToPlayback(video.videourl) },
                            status = video.status,
                            completionNote = video.completionNote,
                            viewModel = viewModel
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = "Back button" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4081),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VideoItem(
    videourl: String,
    onPlayClick: () -> Unit,
    isAdmin: Boolean = false,
    status: String = "pending",
    completionNote: String = "",
    onApproveClick: () -> Unit = {},
    onShareClick: (String) -> Unit = {},
    onDeleteClick: () -> Unit = {},
    viewModel: TaskViewModel,
    modifier: Modifier = Modifier
) {
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    videourl.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "Unnamed Video",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3F51B5)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Video file name: ${videourl.substringAfterLast("/")}" }
                )
                if (isAdmin) {
                    Text(
                        if (status == "approved") "Approved" else "Pending",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == "approved") Color(0xFF4CAF50) else Color(0xFFFFA726),
                        modifier = Modifier.semantics {
                            contentDescription = "Approval status: ${if (status == "approved") "Approved" else "Pending"}"
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onApproveClick,
                        modifier = Modifier.semantics {
                            contentDescription = if (status == "approved") "Unapprove video button" else "Approve video button"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = if (status == "approved") "Unapprove video" else "Approve video",
                            tint = if (status == "approved") Color(0xFFEF5350) else Color(0xFF4CAF50)
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    videoUrl = viewModel.getVideoDownloadUrl(videourl)
                                    videoUrl?.let { url ->
                                        onShareClick(url)
                                    } ?: run {
                                        shareError = "Failed to generate shareable link"
                                    }
                                } catch (e: Exception) {
                                    shareError = "Error generating link: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "Share video button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share video",
                            tint = Color(0xFF3F51B5)
                        )
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.semantics { contentDescription = "Delete video button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Delete video",
                            tint = Color(0xFFEF5350)
                        )
                    }
                } else {
                    Text(
                        if (status == "approved") "Approved" else "Pending",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == "approved") Color(0xFF4CAF50) else Color(0xFFFFA726),
                        modifier = Modifier.semantics {
                            contentDescription = "Approval status: ${if (status == "approved") "Approved" else "Pending"}"
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier.semantics { contentDescription = "Play video button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play video",
                            tint = Color(0xFF3F51B5)
                        )
                    }
                }
            }
            if (completionNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Note: $completionNote",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF757575)),
                    modifier = Modifier.semantics { contentDescription = "Completion note: $completionNote" }
                )
            }
            if (shareError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    shareError!!,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier.semantics { contentDescription = "Share error: $shareError" }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoRecordScreen(
    taskId: String,
    viewModel: TaskViewModel,
    analytics: FirebaseAnalytics,
    userId: String,
    onBack: () -> Unit,
    onNavigateToCompletionNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasAudioPermission by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }
    var recordingTime by remember { mutableStateOf(0L) }
    var timer by remember { mutableStateOf<CountDownTimer?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    val task = viewModel.tasks.collectAsState().value.find { it.id == taskId }
    val taskDuration = (task?.durationSeconds?.toLong()?.times(1000L) ?: 30000L).coerceAtLeast(1000L)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (!hasCameraPermission || !hasAudioPermission) {
            recordingError = "Camera or audio permission denied."
            if (!shouldShowRequestPermissionRationale(context as ComponentActivity, Manifest.permission.CAMERA) ||
                !shouldShowRequestPermissionRationale(context as ComponentActivity, Manifest.permission.RECORD_AUDIO)) {
                showPermissionDeniedDialog = true
            }
        }
        analytics.logEvent("permission_request") {
            param("camera_granted", hasCameraPermission.toString())
            param("audio_granted", hasAudioPermission.toString())
        }
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        context as ComponentActivity,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                } catch (e: Exception) {
                    recordingError = "Failed to initialize camera: ${e.message}"
                    analytics.logEvent("camera_error") {
                        param("error", e.message ?: "unknown")
                        param("task_id", taskId)
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            timer?.cancel()
            recordingTime = 0L
            timer = object : CountDownTimer(taskDuration, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    recordingTime = (taskDuration - millisUntilFinished) / 1000
                }

                override fun onFinish() {
                    recording?.stop()
                    isRecording = false
                    timer = null
                }
            }.start()
        } else {
            timer?.cancel()
            timer = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            timer?.cancel()
            recording?.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Video - ${task?.title ?: "Task $taskId"}", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()

                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasCameraPermission && hasAudioPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, Color(0xFF3F51B5), RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Camera preview" }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Recording Time: ${recordingTime}s / ${taskDuration / 1000}s",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = if (isRecording) Color(0xFFFF4081) else Color(0xFF3F51B5)
                    ),
                    modifier = Modifier.semantics { contentDescription = "Recording time: $recordingTime seconds of ${taskDuration / 1000} seconds" }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (!isRecording) {
                            val outputFile = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
                            val outputOptions = FileOutputOptions.Builder(outputFile).build()

                            videoCapture?.let { capture ->
                                recording = capture.output
                                    .prepareRecording(context, outputOptions)
                                    .withAudioEnabled()
                                    .start(ContextCompat.getMainExecutor(context)) { event ->
                                        when (event) {
                                            is VideoRecordEvent.Start -> {
                                                isRecording = true
                                                analytics.logEvent("video_recording_started") {
                                                    param("task_id", taskId)
                                                    param("user_id", userId)
                                                }
                                            }
                                            is VideoRecordEvent.Finalize -> {
                                                isRecording = false
                                                recording = null
                                                if (event.hasError()) {
                                                    recordingError = "Recording failed: ${event.error}"
                                                    analytics.logEvent("video_recording_error") {
                                                        param("task_id", taskId)
                                                        param("error", event.error.toString())
                                                    }
                                                    outputFile.delete()
                                                } else {
                                                    onNavigateToCompletionNote(outputFile.absolutePath)
                                                    analytics.logEvent("video_recording_completed") {
                                                        param("task_id", taskId)
                                                        param("duration", recordingTime.toString())
                                                    }
                                                }
                                            }
                                        }
                                    }
                            }
                        } else {
                            recording?.stop()
                            isRecording = false
                            analytics.logEvent("video_recording_stopped") {
                                param("task_id", taskId)
                                param("duration", recordingTime.toString())
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .semantics { contentDescription = if (isRecording) "Stop recording button" else "Start recording button" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFEF5350) else Color(0xFF3F51B5),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isRecording) "Stop Recording" else "Start Recording",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (recordingError != null) {
                    Text(
                        recordingError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "Recording error: $recordingError" }
                    )
                }
            } else {
                Text(
                    "Camera and audio permissions are required to record video.",
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier.semantics { contentDescription = "Permission required message" }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .semantics { contentDescription = "Request permissions button" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F51B5),
                        contentColor = Color.White
                    )
                ) {
                    Text("Request Permissions", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Permissions Required") },
            text = { Text("Camera and audio permissions are required. Please enable them in app settings.") },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                        showPermissionDeniedDialog = false
                    },
                    modifier = Modifier.semantics { contentDescription = "Open settings button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F51B5)
                    )
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showPermissionDeniedDialog = false
                        onBack()
                    },
                    modifier = Modifier.semantics { contentDescription = "Cancel button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletionNoteScreen(
    taskId: String,
    videoPath: String,
    viewModel: TaskViewModel,
    analytics: FirebaseAnalytics,
    userId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var completionNote by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Completion Note", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = completionNote,
                onValueChange = { completionNote = it },
                label = { Text("Completion Note") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .semantics { contentDescription = "Completion note input field" },
                enabled = !isUploading,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color(0xFF3F51B5),
                    focusedLabelColor = Color(0xFF3F51B5),
                    unfocusedContainerColor = Color.Transparent
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (errorMessage != null || uploadError != null) {
                Text(
                    errorMessage ?: uploadError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "Error message: ${errorMessage ?: uploadError}" }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    isUploading = true
                    uploadError = null
                    coroutineScope.launch {
                        try {
                            viewModel.uploadVideo(videoPath, taskId, userId, completionNote)
                            analytics.logEvent("video_upload_initiated") {
                                param("task_id", taskId)
                                param("user_id", userId)
                            }
                            onBack()
                        } catch (e: Exception) {
                            uploadError = "Upload failed: ${e.message}"
                            analytics.logEvent("video_upload_error") {
                                param("task_id", taskId)
                                param("error", e.message ?: "unknown")
                            }
                        } finally {
                            isUploading = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = "Submit video button" },
                shape = RoundedCornerShape(12.dp),
                enabled = !isUploading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3F51B5),
                    contentColor = Color.White
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text("Submit Video", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlaybackScreen(
    fileName: String,
    viewModel: TaskViewModel,
    analytics: FirebaseAnalytics,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    LaunchedEffect(fileName) {
        try {
            videoUrl = viewModel.getVideoDownloadUrl(fileName)
            videoUrl?.let {
                exoPlayer.setMediaItem(MediaItem.fromUri(it))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                analytics.logEvent("video_playback_started") {
                    param("file_name", fileName)
                }
            } ?: run {
                errorMessage = "Failed to load video URL"
                analytics.logEvent("video_playback_error") {
                    param("file_name", fileName)
                    param("error", "URL null")
                }
            }
        } catch (e: Exception) {
            errorMessage = "Error loading video: ${e.message}"
            analytics.logEvent("video_playback_error") {
                param("file_name", fileName)
                param("error", e.message ?: "unknown")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Playback", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (videoUrl != null) {
                AndroidView(
                    factory = {
                        PlayerView(context).apply {
                            player = exoPlayer
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .semantics { contentDescription = "Video player" }
                )
            } else if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { contentDescription = "Error message: $errorMessage" }
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading video" }
                )
            }
        }
    }
}


@Composable
fun TaskCard(
    task: Task,
    isAdmin: Boolean = false,
    onClickListener: () -> Unit = {},
    onDeleteClickListener: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clickable { if (!isAdmin) onClickListener() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Task details: ${task.title}" }
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3F51B5)
                    ),
                    modifier = Modifier.semantics { contentDescription = "Task title: ${task.title}" }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    task.description,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF757575)),
                    modifier = Modifier.semantics { contentDescription = "Task description: ${task.description}" }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Points: ${task.points}",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF4CAF50)),
                    modifier = Modifier.semantics { contentDescription = "Task points: ${task.points}" }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Duration: ${task.durationSeconds}s",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF757575)),
                    modifier = Modifier.semantics { contentDescription = "Task duration: ${task.durationSeconds} seconds" }
                )
            }
            if (isAdmin) {
                IconButton(
                    onClick = onDeleteClickListener,
                    modifier = Modifier.semantics { contentDescription = "Delete task button" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete task",
                        tint = Color(0xFFEF5350)
                    )
                }
            }
        }
    }
}