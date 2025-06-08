package com.indianlegend.videoquest

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope

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
    val path: String = "", // This will now be the Firebase Storage URL
    val taskId: String = "",
    val timestamp: Long = 0,
    val isApproved: Boolean = false,
    val userId: String = "",
    val completionNote: String = ""
)

// TaskViewModel
class TaskViewModel : ViewModel() {
    private val db: FirebaseFirestore = Firebase.firestore
    private val storage: FirebaseStorage = Firebase.storage

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _recordedVideos = MutableStateFlow<List<VideoMetadata>>(emptyList())
    val recordedVideos: StateFlow<List<VideoMetadata>> = _recordedVideos.asStateFlow()

    private val _userPoints = MutableStateFlow<Map<String, Int>>(emptyMap())
    val userPoints: StateFlow<Map<String, Int>> = _userPoints.asStateFlow()

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            // Fetch tasks
            val taskSnapshot = db.collection("tasks").get().await()
            _tasks.value = taskSnapshot.documents.mapNotNull { doc ->
                try {
                    Task(
                        id = doc.id,
                        title = doc.getString("title") ?: "",
                        description = doc.getString("description") ?: "",
                        points = doc.getLong("points")?.toInt() ?: 0,
                        durationSeconds = doc.getLong("durationSeconds")?.toInt() ?: 30
                    )
                } catch (e: Exception) {
                    null
                }
            }

            // Fetch videos
            val videoSnapshot = db.collection("videos").get().await()
            _recordedVideos.value = videoSnapshot.documents.mapNotNull { doc ->
                try {
                    VideoMetadata(
                        path = doc.getString("path") ?: "",
                        taskId = doc.getString("taskId") ?: "",
                        timestamp = doc.getLong("timestamp") ?: 0L,
                        isApproved = doc.getBoolean("isApproved") ?: false,
                        userId = doc.getString("userId") ?: "",
                        completionNote = doc.getString("completionNote") ?: ""
                    )
                } catch (e: Exception) {
                    null
                }
            }

            // Fetch user points
            val userSnapshot = db.collection("users").get().await()
            _userPoints.value = userSnapshot.documents.associate { doc ->
                doc.id to (doc.getLong("points")?.toInt() ?: 0)
            }
        }
    }

    fun clearData() {
        _tasks.value = emptyList()
        _recordedVideos.value = emptyList()
        _userPoints.value = emptyMap()
    }

    suspend fun addTask(task: Task) {
        val taskData = hashMapOf(
            "title" to task.title,
            "description" to task.description,
            "points" to task.points,
            "durationSeconds" to task.durationSeconds
        )
        db.collection("tasks").document(task.id).set(taskData).await()
        _tasks.value = _tasks.value + task
    }

    suspend fun deleteTask(task: Task) {
        db.collection("tasks").document(task.id).delete().await()
        _tasks.value = _tasks.value - task
    }

    suspend fun addVideo(video: VideoMetadata, localFile: File) {
        // Upload video to Firebase Storage
        val storageRef = storage.reference.child("videos/${video.userId}/${video.taskId}_${System.currentTimeMillis()}.mp4")
        val uploadTask = storageRef.putFile(Uri.fromFile(localFile)).await()
        val downloadUrl = uploadTask.storage.downloadUrl.await().toString()

        // Save video metadata to Firestore with the Storage URL
        val videoData = hashMapOf(
            "path" to downloadUrl,
            "taskId" to video.taskId,
            "timestamp" to video.timestamp,
            "isApproved" to video.isApproved,
            "userId" to video.userId,
            "completionNote" to video.completionNote
        )
        val docId = "${video.userId}_${video.taskId}_${video.timestamp}"
        db.collection("videos").document(docId).set(videoData).await()

        _recordedVideos.value = _recordedVideos.value + video.copy(path = downloadUrl)
    }

    suspend fun approveVideo(video: VideoMetadata, isApproved: Boolean) {
        val docId = "${video.userId}_${video.taskId}_${video.timestamp}"
        db.collection("videos").document(docId).update("isApproved", isApproved).await()
        _recordedVideos.value = _recordedVideos.value.map {
            if (it.userId == video.userId && it.taskId == video.taskId && it.timestamp == video.timestamp) {
                it.copy(isApproved = isApproved)
            } else {
                it
            }
        }

        // Update user points if approved
        if (isApproved) {
            val pointsToAdd = getTaskPoints(video.taskId)
            updateUserPoints(video.userId, pointsToAdd)
        }
    }

    suspend fun updateUserPoints(userId: String, pointsToAdd: Int) {
        val userRef = db.collection("users").document(userId)
        val userDoc = userRef.get().await()
        val currentPoints = userDoc.getLong("points")?.toInt() ?: 0
        val updatedPoints = currentPoints + pointsToAdd
        userRef.set(hashMapOf("points" to updatedPoints)).await()

        _userPoints.value = _userPoints.value.toMutableMap().apply {
            this[userId] = updatedPoints
        }
    }

    fun getTaskPoints(taskId: String): Int {
        return tasks.value.find { it.id == taskId }?.points ?: 0
    }

    suspend fun initializeUserPoints(userId: String) {
        val userRef = db.collection("users").document(userId)
        val userDoc = userRef.get().await()
        if (!userDoc.exists()) {
            userRef.set(hashMapOf("points" to 50)).await()
            _userPoints.value = _userPoints.value.toMutableMap().apply {
                this[userId] = 50
            }
        }
    }
}

// Main Activity
class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var analytics: FirebaseAnalytics
    private val taskViewModel: TaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth
        analytics = Firebase.analytics

        // Initialize default tasks if needed
        lifecycleScope.launch {
            val taskCount = taskViewModel.tasks.value.size
            if (taskCount == 0) {
                taskViewModel.addTask(Task("task1", "Task 1", "Complete this task", 10, 30))
                taskViewModel.addTask(Task("task2", "Task 2", "Record a video", 20, 60))
            }
        }

        setContent {
            VideoQuestTheme {
                VideoQuestApp(auth, analytics, taskViewModel)
            }
        }
    }
}

// Theme
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

// Navigation
@Composable
fun VideoQuestApp(auth: FirebaseAuth, analytics: FirebaseAnalytics, viewModel: TaskViewModel) {
    val navController = rememberNavController()
    var currentUser by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(auth.currentUser) {
        auth.currentUser?.let {
            currentUser = User(
                id = it.uid,
                email = it.email ?: "",
                role = if (it.email == "admin@example.com") "admin" else "user",
                points = viewModel.userPoints.value[it.uid] ?: 50
            )
            viewModel.initializeUserPoints(it.uid)
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
                    currentUser = User(
                        id = auth.currentUser?.uid ?: "",
                        email = auth.currentUser?.email ?: "",
                        role = role,
                        points = viewModel.userPoints.value[auth.currentUser?.uid] ?: 50
                    )
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
                onNavigateToPlayback = { videoPath ->
                    val encodedVideoPath = Uri.encode(videoPath)
                    navController.navigate("playback/$encodedVideoPath")
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
                onNavigateToPlayback = { videoPath ->
                    val encodedVideoPath = Uri.encode(videoPath)
                    navController.navigate("playback/$encodedVideoPath")
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
                }
            )
        }
        composable("points") {
            PointsScreen(
                user = currentUser ?: User(),
                recordedVideos = viewModel.recordedVideos,
                userPoints = viewModel.userPoints,
                onNavigateToPlayback = { videoPath ->
                    val encodedVideoPath = Uri.encode(videoPath)
                    navController.navigate("playback/$encodedVideoPath")
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
                }
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
        composable("playback/{videoPath}") { backStackEntry ->
            val encodedVideoPath = backStackEntry.arguments?.getString("videoPath") ?: ""
            val videoPath = Uri.decode(encodedVideoPath)
            VideoPlaybackScreen(
                videoPath = videoPath,
                analytics = analytics,
                onBack = {
                    if (currentUser?.role == "admin") {
                        navController.navigate("admin") {
                            popUpTo("playback/$encodedVideoPath") { inclusive = true }
                        }
                    } else {
                        navController.navigate("task") {
                            popUpTo("playback/$encodedVideoPath") { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}

// Screens
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
                            auth.signInWithEmailAndPassword(email, password)
                                .addOnSuccessListener {
                                    isLoading = false
                                    val role = if (email == "admin@example.com") "admin" else "user"
                                    onLoginSuccess(role)
                                }
                                .addOnFailureListener { e ->
                                    isLoading = false
                                    errorMessage = e.message ?: "Login failed"
                                    analytics.logEvent("login_error") {
                                        param("error_message", errorMessage!!)
                                        param("email", email)
                                    }
                                }
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF4081),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
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
                            Icons.Default.ArrowBack,
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
                            auth.createUserWithEmailAndPassword(email, password)
                                .addOnSuccessListener {
                                    isLoading = false
                                    analytics.logEvent(FirebaseAnalytics.Event.SIGN_UP) {
                                        param(FirebaseAnalytics.Param.METHOD, "email")
                                    }
                                    Toast.makeText(context, "Registration successful", Toast.LENGTH_SHORT).show()
                                    onRegisterSuccess()
                                }
                                .addOnFailureListener { e ->
                                    isLoading = false
                                    errorMessage = e.message ?: "Registration failed"
                                    analytics.logEvent("register_error") {
                                        param("error_message", errorMessage!!)
                                        param("email", email)
                                    }
                                }
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
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
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
                            onRecordClick = { onNavigateToVideoRecord(task.id) }
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
                            videoPath = video.path,
                            onPlayClick = { onNavigateToPlayback(video.path) },
                            isApproved = video.isApproved,
                            completionNote = video.completionNote
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
                Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(20.dp))
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
                Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
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
    var showDeleteDialog by remember { mutableStateOf<Task?>(null) }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDescription by remember { mutableStateOf("") }
    var newTaskPoints by remember { mutableStateOf("") }
    var newTaskDuration by remember { mutableStateOf("30") }
    val tasks by viewModel.tasks.collectAsState()
    val recordedVideos by viewModel.recordedVideos.collectAsState()

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
                Icon(Icons.Default.Add, contentDescription = "Add new task")
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
                            onDeleteClick = { showDeleteDialog = task }
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

            if (recordedVideos.isEmpty()) {
                Text(
                    "No videos recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "No videos message" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(recordedVideos) { video ->
                        VideoItem(
                            videoPath = video.path,
                            onPlayClick = { onNavigateToPlayback(video.path) },
                            isAdmin = true,
                            isApproved = video.isApproved,
                            completionNote = video.completionNote,
                            onApproveClick = {
                                viewModel.viewModelScope.launch {
                                    val wasApproved = video.isApproved
                                    viewModel.approveVideo(video, !video.isApproved)
                                    if (!wasApproved && !video.isApproved) {
                                        val pointsToAdd = viewModel.getTaskPoints(video.taskId)
                                        onUserPointsUpdate(user.points + pointsToAdd)
                                    }
                                }
                            },
                            onShareClick = {
                                val context = LocalContext.current
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, video.path)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Video URL"))
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
                Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(20.dp))
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
                Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
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
                        label = { Text("Title") },
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
                        value = newTaskDuration,
                        onValueChange = { newTaskDuration = it },
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
                        val duration = newTaskDuration.toIntOrNull() ?: 30
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
                            }
                            newTaskTitle = ""
                            newTaskDescription = ""
                            newTaskPoints = ""
                            newTaskDuration = "30"
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

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Task") },
            text = { Text("Are you sure you want to delete ${showDeleteDialog!!.title}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.viewModelScope.launch {
                            viewModel.deleteTask(showDeleteDialog!!)
                        }
                        showDeleteDialog = null
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
                    onClick = { showDeleteDialog = null },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsScreen(
    user: User,
    recordedVideos: StateFlow<List<VideoMetadata>>,
    userPoints: StateFlow<Map<String, Int>>,
    onNavigateToPlayback: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val videos by recordedVideos.collectAsState()
    val points by userPoints.collectAsState()
    val filteredVideos = videos.filter { it.userId == user.id }
    val userPointsValue = points[user.id] ?: user.points

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
                            Icons.Default.ArrowBack,
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
                        Icons.Outlined.AccountCircle,
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
                        "Points: $userPointsValue",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier.semantics { contentDescription = "User points: $userPointsValue" }
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
                            videoPath = video.path,
                            onPlayClick = { onNavigateToPlayback(video.path) },
                            isApproved = video.isApproved,
                            completionNote = video.completionNote
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
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
    var isRecording by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }
    var recordingSuccess by remember { mutableStateOf<String?>(null) }
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
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!hasCameraPermission) {
            recordingError = "Camera permission denied."
            if (!shouldShowRequestPermissionRationale(context as ComponentActivity, Manifest.permission.CAMERA)) {
                showPermissionDeniedDialog = true
            }
        }
        analytics.logEvent("permission_request") {
            param("camera_granted", hasCameraPermission.toString())
        }
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Video - Task $taskId", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
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
            if (hasCameraPermission) {
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

                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0xFFEF5350), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "REC ${recordingTime}s / ${taskDuration / 1000}s",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { contentDescription = "Recording timer: $recordingTime seconds of ${taskDuration / 1000} seconds" }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!isRecording) {
                            videoCapture?.let { capture ->
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                val videoFile = File(context.filesDir, "video_$timestamp.mp4")
                                val outputOptions = FileOutputOptions.Builder(videoFile).build()

                                recording = capture.output
                                    .prepareRecording(context, outputOptions)
                                    .start(ContextCompat.getMainExecutor(context)) { event ->
                                        when (event) {
                                            is VideoRecordEvent.Start -> {
                                                isRecording = true
                                                recordingError = null
                                                recordingSuccess = null
                                                analytics.logEvent("video_record_start") {
                                                    param("task_id", taskId)
                                                }
                                            }
                                            is VideoRecordEvent.Finalize -> {
                                                isRecording = false
                                                if (event.hasError()) {
                                                    recordingError = "Recording failed: ${event.error}"
                                                    analytics.logEvent("video_record_error") {
                                                        param("error", event.error.toString())
                                                        param("task_id", taskId)
                                                    }
                                                } else {
                                                    recordingSuccess = "Video saved successfully"
                                                    val videoPath = videoFile.absolutePath
                                                    onNavigateToCompletionNote(videoPath)
                                                    analytics.logEvent("video_record_success") {
                                                        param("task_id", taskId)
                                                        param("video_path", videoPath)
                                                    }
                                                }
                                            }
                                        }
                                    }
                            } ?: run {
                                recordingError = "Camera not initialized."
                                analytics.logEvent("camera_not_initialized") {
                                    param("task_id", taskId)
                                }
                            }
                        } else {
                            recording?.stop()
                            isRecording = false
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
                    Text(
                        if (isRecording) "Stop Recording" else "Start Recording",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    "Camera permission required.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = "Camera permission required message" }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .semantics { contentDescription = "Retry camera permission button" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F51B5),
                        contentColor = Color.White
                    )
                ) {
                    Text("Retry Permission", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (recordingError != null) {
                Text(
                    recordingError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "Recording error message: $recordingError" }
                )
            }

            if (recordingSuccess != null) {
                Text(
                    recordingSuccess!!,
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "Recording success message: $recordingSuccess" }
                )
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
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Permission Denied") },
            text = { Text("Camera permission is permanently denied. Please enable it in Settings.") },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.parse("package:${context.packageName}")
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
                    onClick = { showPermissionDeniedDialog = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel permission dialog button" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderFuture.get()?.unbindAll()
            executor.shutdown()
            recording?.stop()
            timer?.cancel()
        }
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
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Completion Note - Task $taskId", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
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
                    Text(
                        "Write a note about your task completion",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3F51B5)
                        ),
                        modifier = Modifier.semantics { contentDescription = "Instruction for completion note" }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = completionNote,
                        onValueChange = { completionNote = it },
                        label = { Text("Completion Note") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Completion note input field" },
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
                            if (completionNote.isBlank()) {
                                errorMessage = "Completion note cannot be empty"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            viewModel.viewModelScope.launch {
                                viewModel.addVideo(
                                    VideoMetadata(
                                        path = videoPath,
                                        taskId = taskId,
                                        timestamp = System.currentTimeMillis(),
                                        userId = userId,
                                        completionNote = completionNote
                                    ),
                                    File(videoPath)
                                )
                                analytics.logEvent("completion_note_added") {
                                    param("task_id", taskId)
                                    param("video_path", videoPath)
                                }
                                isLoading = false
                                Toast.makeText(context, "Note submitted successfully", Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics { contentDescription = "Submit note button" },
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
                            Text("Submit Note", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlaybackScreen(
    videoPath: String,
    analytics: FirebaseAnalytics,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoPath)))
            prepare()
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Toast.makeText(context, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
                    analytics.logEvent("playback_error") {
                        param("error_message", error.message ?: "unknown")
                        param("video_path", videoPath)
                    }
                }
            })
        }
    }

    LaunchedEffect(Unit) {
        analytics.logEvent("video_playback") {
            param("video_path", videoPath)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Play Video", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF3F51B5)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back button" }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, Color(0xFF3F51B5), RoundedCornerShape(16.dp))
            ) {
                AndroidView(
                    factory = {
                        PlayerView(context).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Video player for $videoPath" }
                )
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
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }
}

@Composable
fun TaskCard(
    task: Task,
    isAdmin: Boolean = false,
    onRecordClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp)),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title.takeIf { it.isNotBlank() } ?: "Untitled Task",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3F51B5)
                    ),
                    modifier = Modifier.semantics { contentDescription = "Task title: ${task.title}" }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    task.description.takeIf { it.isNotBlank() } ?: "No description",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF757575)),
                    modifier = Modifier.semantics { contentDescription = "Task description: ${task.description}" }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Points: ${task.points}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFFFF4081)),
                    modifier = Modifier.semantics { contentDescription = "Task points: ${task.points}" }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Duration: ${task.durationSeconds} seconds",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF757575)),
                    modifier = Modifier.semantics { contentDescription = "Task duration: ${task.durationSeconds} seconds" }
                )
            }

            if (isAdmin) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.semantics { contentDescription = "Delete task button" }
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Delete task",
                        tint = Color(0xFFEF5350)
                    )
                }
            } else {
                IconButton(
                    onClick = onRecordClick,
                    modifier = Modifier.semantics { contentDescription = "Record video button" }
                ) {
                    Icon(
                        Icons.Default.VideoCall,
                        contentDescription = "Record video for task",
                        tint = Color(0xFF3F51B5)
                    )
                }
            }
        }
    }
}

@Composable
fun VideoItem(
    videoPath: String,
    onPlayClick: () -> Unit,
    isAdmin: Boolean = false,
    isApproved: Boolean = false,
    completionNote: String = "",
    onApproveClick: () -> Unit = {},
    onShareClick: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier
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
                    "Video_${videoPath.hashCode()}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF3F51B5)),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Video identifier: Video_${videoPath.hashCode()}" }
                )
                if (isAdmin) {
                    Text(
                        if (isApproved) "Approved" else "Pending",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isApproved) Color(0xFF4CAF50) else Color(0xFFFFA726),
                        modifier = Modifier.semantics { contentDescription = "Approval status: ${if (isApproved) "Approved" else "Pending"}" }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onApproveClick,
                        modifier = Modifier.semantics { contentDescription = if (isApproved) "Unapprove video button" else "Approve video button" }
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = if (isApproved) "Unapprove video" else "Approve video",
                            tint = if (isApproved) Color(0xFF757575) else Color(0xFF4CAF50)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onShareClick as () -> Unit,
                        modifier = Modifier.semantics { contentDescription = "Share video button" }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share video",
                            tint = Color(0xFF3F51B5)
                        )
                    }
                } else {
                    Text(
                        if (isApproved) "Approved" else "Pending Approval",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isApproved) Color(0xFF4CAF50) else Color(0xFFFFA726),
                        modifier = Modifier.semantics { contentDescription = "Approval status: ${if (isApproved) "Approved" else "Pending Approval"}" }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.semantics { contentDescription = "Play video button" }
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color(0xFF3F51B5)
                    )
                }
            }
            if (completionNote.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Completion Note: $completionNote",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF757575)),
                    modifier = Modifier.semantics { contentDescription = "Completion note: $completionNote" }
                )
            }
        }
    }
}