package com.sendspin.sender.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sendspin.sender.R
import com.sendspin.sender.mass.MassAccountState

/**
 * The page that signs the phone in to Music Assistant.
 *
 * The user comes here from the option on the first page. The address of the
 * server comes from the search, so it is filled in already, and the user
 * types only the name and the password.
 *
 * The page is a state of the activity, not a second activity. The back
 * gesture therefore does what Cancel does, because the system would
 * otherwise leave the app.
 */
@Composable
fun SignInScreen(
    account: MassAccountState,
    onSignIn: (serverUrl: String, username: String, password: String) -> Unit,
    onCancel: () -> Unit,
    onServerUrlChange: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    BackHandler(onBack = onCancel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.title_sign_in),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.account_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = account.serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text(stringResource(R.string.label_account_server)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.label_account_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.label_account_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    onSignIn(account.serverUrl, username, password)
                    password = ""
                },
                enabled = !account.signingIn && account.serverUrl.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (account.signingIn) {
                            R.string.action_signing_in
                        } else {
                            R.string.action_sign_in
                        },
                    ),
                )
            }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        account.error?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
