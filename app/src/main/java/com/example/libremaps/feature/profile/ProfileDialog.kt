package com.example.libremaps.feature.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.libremaps.core.data.UserSessionManager
import com.example.libremaps.core.model.UpdateProfileRequest
import com.example.libremaps.core.network.ApiClient
import com.example.libremaps.feature.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

private const val AVATAR_SIZE_PX = 256

@Composable
fun ProfileDialog(
    token: String,
    vm: AuthViewModel = viewModel(),
    onDismiss: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { UserSessionManager(context) }
    val auth = "Bearer $token"

    var email by remember { mutableStateOf(sessionManager.getUserEmail() ?: "Utilizador") }
    var savedName by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Bitmap?>(null) }
    var profileLoading by remember { mutableStateOf(true) }
    var profileBusy by remember { mutableStateOf(false) }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }

    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }

    var showChangePassword by rememberSaveable { mutableStateOf(false) }
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val state = vm.state

    LaunchedEffect(token) {
        try {
            val res = ApiClient.api.getProfile(auth)
            if (res.isSuccessful) {
                res.body()?.let { p ->
                    email = p.email
                    savedName = p.displayName.orEmpty()
                    nameInput = savedName
                    if (p.hasAvatar) {
                        val avatarRes = ApiClient.api.getAvatar(auth)
                        if (avatarRes.isSuccessful) {
                            val bytes = withContext(Dispatchers.IO) { avatarRes.body()?.bytes() }
                            avatar = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }
                }
            } else {
                profileError = "Não foi possível carregar o perfil."
            }
        } catch (_: Exception) {
            profileError = "Sem ligação ao servidor."
        } finally {
            profileLoading = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            profileBusy = true
            profileError = null
            profileMessage = null
            try {
                val bytes = prepareAvatar(context, uri)
                if (bytes == null) {
                    profileError = "Não foi possível ler a imagem."
                } else {
                    val res = ApiClient.api.uploadAvatar(
                        auth,
                        bytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    if (res.isSuccessful) {
                        avatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        profileMessage = "Foto atualizada."
                    } else {
                        profileError = "Não foi possível guardar a foto (${res.code()})."
                    }
                }
            } catch (_: Exception) {
                profileError = "Sem ligação ao servidor."
            } finally {
                profileBusy = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            scope.launch {
                profileBusy = true
                profileError = null
                profileMessage = null
                try {
                    val bytes = prepareAvatar(bitmap)
                    val res = ApiClient.api.uploadAvatar(
                        auth,
                        bytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    if (res.isSuccessful) {
                        avatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        profileMessage = "Foto atualizada com sucesso."
                    } else {
                        profileError = "Não foi possível guardar a foto (${res.code()})."
                    }
                } catch (_: Exception) {
                    profileError = "Sem ligação ao servidor."
                } finally {
                    profileBusy = false
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraLauncher.launch(null)
        } else {
            profileError = "É necessária a permissão da câmara para tirar fotos."
        }
    }

    val trimmedName = nameInput.trim()
    val nameValid = trimmedName.length in 2..30
    val nameChanged = trimmedName != savedName

    val scrollState = rememberScrollState()

    LaunchedEffect(showChangePassword) {
        if (showChangePassword) {
            delay(150)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text("Alterar Foto de Perfil") },
            text = { Text("Como pretende escolher a sua foto?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImageSourceDialog = false
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                ) {
                    Text("Tirar Foto")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImageSourceDialog = false
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Text("Escolher da Galeria")
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = "Perfil de Utilizador",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !profileBusy) {
                                showImageSourceDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = avatar
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Foto de perfil",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Sem foto de perfil",
                                        modifier = Modifier.size(56.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (profileBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        }
                    }
                }

                TextButton(
                    onClick = { showImageSourceDialog = true },
                    enabled = !profileBusy,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Alterar foto")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        if (it.length <= 30) nameInput = it
                        profileMessage = null
                        profileError = null
                    },
                    label = { Text("Nome de utilizador") },
                    singleLine = true,
                    enabled = !profileLoading,
                    isError = nameInput.isNotBlank() && !nameValid,
                    supportingText = {
                        if (nameInput.isNotBlank() && !nameValid) Text("Entre 2 e 30 caracteres")
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        scope.launch {
                            profileBusy = true
                            profileError = null
                            profileMessage = null
                            try {
                                val res = ApiClient.api.updateProfile(auth, UpdateProfileRequest(trimmedName))
                                if (res.isSuccessful) {
                                    savedName = trimmedName
                                    nameInput = trimmedName
                                    profileMessage = "Nome atualizado."
                                } else {
                                    profileError = "Não foi possível guardar o nome (${res.code()})."
                                }
                            } catch (_: Exception) {
                                profileError = "Sem ligação ao servidor."
                            } finally {
                                profileBusy = false
                            }
                        }
                    },
                    enabled = !profileBusy && !profileLoading && nameValid && nameChanged,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Guardar nome")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Email da conta:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                profileMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                profileError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!showChangePassword) {
                    OutlinedButton(
                        onClick = { showChangePassword = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Alterar Palavra-passe")
                    }
                } else {
                    Text(
                        text = "Alterar Palavra-passe",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("Palavra-passe Atual") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Nova Palavra-passe") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Ocultar palavra-passe" else "Mostrar palavra-passe"
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirmar Nova Palavra-passe") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showChangePassword = false }) { Text("Cancelar") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                vm.changePassword(
                                    email = email,
                                    oldPassword = oldPassword,
                                    newPassword = newPassword,
                                    confirmPassword = confirmPassword,
                                    onSuccess = {
                                        showChangePassword = false
                                        oldPassword = ""
                                        newPassword = ""
                                        confirmPassword = ""
                                    }
                                )
                            },
                            enabled = !state.loading && oldPassword.isNotBlank() &&
                                    newPassword.isNotBlank() && confirmPassword.isNotBlank()
                        ) { Text("Guardar") }
                    }
                }

                state.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.successMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        vm.logout(sessionManager)
                        onLogout()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Terminar Sessão")
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Fechar")
                }
            }
        }
    }
}

private suspend fun prepareAvatar(bitmap: Bitmap): ByteArray = withContext(Dispatchers.IO) {
    val side = minOf(bitmap.width, bitmap.height)
    val square = Bitmap.createBitmap(
        bitmap,
        (bitmap.width - side) / 2,
        (bitmap.height - side) / 2,
        side,
        side
    )
    val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true)

    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}

private suspend fun prepareAvatar(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2

        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null

        prepareAvatar(decoded)
    } catch (_: Exception) {
        null
    }
}
