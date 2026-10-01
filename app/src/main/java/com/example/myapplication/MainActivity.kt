package com.example.myapplication

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * Data model stored in Firebase Realtime Database (JSON) at:
 *   users/{UID} -> { "name": "...", "phone": "...", "email": "...", "role": "buyer" | "seller" }
 * Default values are required so Firebase can deserialize the JSON back into this class.
 */
data class UserProfile(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val role: String = "buyer"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Firebase helpers
// ---------------------------------------------------------------------------------------------

private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

// If your database is NOT in us-central1, use FirebaseDatabase.getInstance("<your database URL>")
private val usersRef get() = FirebaseDatabase.getInstance().getReference("users")

/** Reads users/{uid} from the Realtime Database and returns it as a UserProfile (or null). */
private fun loadProfile(uid: String, onResult: (UserProfile?) -> Unit, onError: (String) -> Unit) {
    usersRef.child(uid).get()
        .addOnSuccessListener { snapshot -> onResult(snapshot.getValue(UserProfile::class.java)) }
        .addOnFailureListener { onError(it.message ?: "Failed to load profile") }
}

// ---------------------------------------------------------------------------------------------
// Root: decides which screen to show (auth state + role)
// ---------------------------------------------------------------------------------------------

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var checkingSession by remember { mutableStateOf(auth.currentUser != null) }

    // If the user is already logged in from a previous session, restore their profile.
    LaunchedEffect(Unit) {
        val user = auth.currentUser
        if (user != null) {
            loadProfile(
                uid = user.uid,
                onResult = { profile = it; checkingSession = false },
                onError = {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    checkingSession = false
                }
            )
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            checkingSession -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            profile == null -> AuthScreen(onAuthenticated = { profile = it })
            else -> ProfileScreen(
                profile = profile!!,
                onProfileUpdated = { profile = it },
                onLogout = {
                    auth.signOut()
                    profile = null
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Login / Sign-up screen
// ---------------------------------------------------------------------------------------------

@Composable
fun AuthScreen(onAuthenticated: (UserProfile) -> Unit) {
    val context = LocalContext.current

    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("buyer") }
    var loading by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun validate(): Boolean {
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { toast("Enter a valid email"); return false }
        if (password.length < 6) { toast("Password must be at least 6 characters"); return false }
        if (!isLogin) {
            if (name.isBlank()) { toast("Name is required"); return false }
            if (phone.length < 7) { toast("Enter a valid phone number"); return false }
        }
        return true
    }

    /** Login: authenticate, then fetch the profile to verify the role before entering the app. */
    fun login() {
        loading = true
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { result ->
                val uid = result.user!!.uid
                loadProfile(
                    uid = uid,
                    onResult = { p ->
                        loading = false
                        if (p != null) onAuthenticated(p)
                        else { auth.signOut(); toast("No profile found for this account") }
                    },
                    onError = { loading = false; toast(it) }
                )
            }
            .addOnFailureListener { loading = false; toast(it.message ?: "Login failed") }
    }

    /** Sign up: create the auth user, then immediately save the profile JSON under users/{UID}. */
    fun signUp() {
        loading = true
        auth.createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { result ->
                val uid = result.user!!.uid
                val newProfile = UserProfile(
                    name = name.trim(),
                    phone = phone.trim(),
                    email = email.trim(),
                    role = role
                )
                usersRef.child(uid).setValue(newProfile)
                    .addOnSuccessListener {
                        loading = false
                        toast("Account created")
                        onAuthenticated(newProfile)
                    }
                    .addOnFailureListener { loading = false; toast(it.message ?: "Could not save profile") }
            }
            .addOnFailureListener { loading = false; toast(it.message ?: "Sign up failed") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isLogin) "Welcome back" else "Create account",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(24.dp))

        if (!isLogin) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Full name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text("Phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        if (!isLogin) {
            Spacer(Modifier.height(16.dp))
            Text("I am a:", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = role == "buyer", onClick = { role = "buyer" })
                Text("Buyer")
                Spacer(Modifier.width(24.dp))
                RadioButton(selected = role == "seller", onClick = { role = "seller" })
                Text("Seller")
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (validate()) { if (isLogin) login() else signUp() } },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (isLogin) "Log in" else "Sign up")
        }

        TextButton(onClick = { isLogin = !isLogin }, enabled = !loading) {
            Text(if (isLogin) "No account? Sign up" else "Already have an account? Log in")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home / Profile screen (view + edit name and phone)
// ---------------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(
    profile: UserProfile,
    onProfileUpdated: (UserProfile) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(profile.name) }
    var phone by remember { mutableStateOf(profile.phone) }
    var saving by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    /** Updates only the editable fields under users/{UID}. */
    fun save() {
        val uid = auth.currentUser?.uid ?: return
        if (name.isBlank()) { toast("Name cannot be empty"); return }
        if (phone.length < 7) { toast("Enter a valid phone number"); return }

        saving = true
        val updates = mapOf<String, Any>("name" to name.trim(), "phone" to phone.trim())
        usersRef.child(uid).updateChildren(updates)
            .addOnSuccessListener {
                saving = false
                toast("Profile updated")
                onProfileUpdated(profile.copy(name = name.trim(), phone = phone.trim()))
            }
            .addOnFailureListener { saving = false; toast(it.message ?: "Update failed") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (profile.role == "seller") "Seller Home" else "Buyer Home",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(4.dp))
        Text("Signed in as ${profile.email}", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(24.dp))
        Text("My Profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Full name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Phone number") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = profile.email, onValueChange = {},
            label = { Text("Email") }, singleLine = true, readOnly = true, enabled = false,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = profile.role.replaceFirstChar { it.uppercase() }, onValueChange = {},
            label = { Text("Role") }, singleLine = true, readOnly = true, enabled = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Save changes")
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
    }
}