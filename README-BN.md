# NEXGUARD Android

NEXGUARD is an Android Device Companion application designed to securely connect Android devices with an authorized website.

## Core Features

- Secure user authentication.
- Android device registration.
- Unique device pairing code generation.
- Website-based device pairing.
- Permission management.
- Device status monitoring.
- Secure backend communication.
- Admin-authorized device management.

## Android Permissions

The application may request the following permissions when required:

- Contacts
- Microphone
- Location
- Notifications

All permissions must be requested transparently and with the user's consent.

## Privacy and Security

- No hidden recording.
- No unauthorized access to contacts or files.
- Voice recording must be manually started by the user.
- Recording must remain visible to the user.
- Location access requires explicit consent.
- Users can revoke permissions through Android Settings.
- All sensitive communication must use secure APIs.

## Device Pairing Workflow

1. User signs in to the NEXGUARD application.
2. The application requests a unique pairing code from the backend.
3. The user enters the code on the authorized website.
4. The backend validates the code.
5. The device is securely linked to the authorized account.

Pairing codes must be temporary and single-use.

## Uninstall Management

Standard Android applications cannot prevent users from uninstalling them through a website.

Uninstall restrictions are only supported on properly enrolled company-owned Android Enterprise devices using authorized Device Owner management.

## Technology Stack

- Android Native
- Kotlin
- Gradle
- Supabase Authentication
- Supabase PostgreSQL
- Supabase Edge Functions
- GitHub Actions

## Project Restrictions

- No EMI functionality.
- No payment functionality.
- No hidden surveillance.
- No unauthorized device access.

## Development Status

This project is under active development.
