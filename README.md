# Keycloak Phone Number Login Plugin

This plugin enables authentication via phone number and SMS OTP, supporting both browser-based and RESTful API flows.

| Keycloak Version | Plugin Version |
|------------------|----------------|
| 21               | ✅ 1.1.0        |
| 22               | ✅ 26.1.3       |
| 23               | ✅ 26.1.3       |
| 24               | ✅ 26.1.3       |
| 25               | ✅ 26.1.3       |
| 26               | ✅ 26.1.3       |

![Example](./docs/screenshot.png)

## 1. What It Is

The **Keycloak Phone Number Login Plugin** is a custom extension for Keycloak that enables authentication via SMS-based
phone number login. Instead of (or in addition to) traditional username/password logins, this plugin allows end-users to
authenticate using their phone number. The process includes:

- **Phone Number Entry:** Users enter their phone number.
- **Confirmation:** The system displays a formatted version of the number for user confirmation.
- **User Lookup/Creation:** The plugin checks if the user exists (creating one if not).
- **SMS TAN Sending:** A Transaction Authentication Number (TAN) is generated and sent via an external SMS API.
- **TAN Validation:** The user inputs the TAN to complete authentication.
- **Profile Update:** On successful TAN verification, users can update their profile information.

This modular plugin uses a multi-step flow and is designed with separation of concerns in mind, using a dedicated
service layer for SMS and phone number processing.

## 🚀 New: RESTful API Support

The plugin now supports **both browser-based and API-based authentication flows**:
- **Browser Flow**: Traditional web-based login with FreeMarker templates
- **API Flow**: RESTful endpoints for mobile apps, SPAs, and headless clients

**API Endpoints:**
- `POST /realms/{realm}/phone-auth/request-otp` - Request OTP
- `POST /realms/{realm}/phone-auth/verify-otp` - Verify OTP and get tokens
- `POST /realms/{realm}/phone-auth/resend-otp` - Resend OTP

📖 **[Complete API Documentation](./docs/API_DOCUMENTATION.md)**

## New Feature: Dual Authentication Support for SMS Service

The `SmsService` has been enhanced to support both **Basic Authentication** and **OAuth2 Client Credentials Grant** for secure communication with the SMS provider. The plugin prioritizes OAuth2 if the required environment variables are set; otherwise, it falls back to Basic Auth.

## 2. How to Use It

### Installation

#### Downloading the Plugin

You can download the latest plugin JAR from the GitHub artifacts using a `curl` command. For example:

```bash
curl -L -o keycloak-phonenumber-login.jar https://github.com/vymalo/keycloak-phone-number/releases/download/v<version>/keycloak-phonenumber-login-<version>.jar
```

### Deployment

#### Docker Example

```bash
docker run -d \
  --name keycloak \
  -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=password \
  -e SMS_API_URL=http://your-sms-api-url \
  -e SMS_API_COUNTRY_PATTERN='cm|de|fr|in' \
  -e SMS_API_AUTH_USERNAME=someuser \
  -e SMS_API_AUTH_PASSWORD=somepassword \
  -v /path/to/keycloak-phonenumber-login.jar:/opt/keycloak/providers/keycloak-phonenumber-login.jar \
  quay.io/keycloak/keycloak:26.1.2 start-dev
```

### Usage

#### For Browser-based Applications

1. Configure the phone authentication flow in Keycloak Admin Console
2. Add authenticators to your browser flow:
   - SMS -1 Get Phone number
   - SMS -2 Confirm Phone number
   - SMS -3 Choose user by Phone number
   - SMS -4 Send SMS Tan
   - SMS -5 Validate SMS Tan

#### For API/Mobile Applications

Use the RESTful endpoints to implement passwordless phone authentication:

```javascript
// 1. Request OTP
const response = await fetch('/realms/my-realm/phone-auth/request-otp', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    phone: '9876543210',
    regionPrefix: '+91'
  })
});
const { sessionId } = await response.json();

// 2. Verify OTP
const tokenResponse = await fetch('/realms/my-realm/phone-auth/verify-otp', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    sessionId: sessionId,
    code: '123456'
  })
});
const { accessToken, refreshToken } = await tokenResponse.json();
```

See [API Documentation](./docs/API_DOCUMENTATION.md) for complete examples in JavaScript, Python, Swift, and Kotlin.

#### Kubernetes Example

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
spec:
  replicas: 1
  selector:
    matchLabels:
      app: keycloak
  template:
    metadata:
      labels:
        app: keycloak
    spec:
      volumes:
        - name: plugin-volume
          emptyDir: {}
      initContainers:
        - name: download-plugin
          image: curlimages/curl:8.1.2
          env:
            - name: VERSION
              value: 26.1.3
          command:
            - sh
            - -c
            - |
              curl -L -o /plugin/keycloak-phonenumber-login.jar https://github.com/vymalo/keycloak-phone-number/releases/download/v$VERSION/keycloak-phonenumber-login-$VERSION.jar
          volumeMounts:
            - name: plugin-volume
              mountPath: /plugin
      containers:
        - name: keycloak
          image: quay.io/keycloak/keycloak:26.1.2
          ports:
            - containerPort: 8080
          env:
            - name: KEYCLOAK_ADMIN
              value: "admin"
            - name: KEYCLOAK_ADMIN_PASSWORD
              value: "password"
            - name: SMS_API_URL
              value: "http://your-sms-api-url"
            - name: SMS_API_COUNTRY_PATTERN
              value: "cm|de|fr"
            - name: SMS_API_AUTH_USERNAME
              value: "someuser"
            - name: SMS_API_AUTH_PASSWORD
              value: "somepassword"
            - name: OAUTH2_CLIENT_ID
              value: "some-client-id"
            - name: OAUTH2_CLIENT_SECRET
              value: "some-client-secret"
            - name: OAUTH2_TOKEN_ENDPOINT
              value: "http://token-mock:8080/token"
          volumeMounts:
            - name: plugin-volume
              mountPath: /opt/keycloak/providers
```

*Tip:* For Kubernetes, it's more common to use a sidecar or init container to download the plugin from GitHub and copy
it into the appropriate directory.

---

## 3. Environment Variables

The following environment variables are used by the plugin:

- **KEYCLOAK_ADMIN**: The administrator username for Keycloak.  
- **KEYCLOAK_ADMIN_PASSWORD**: The administrator password for Keycloak.  
- **KC_LOG_CONSOLE_COLOR**: Enables colored logging in the console (set to `'true'` or `'false'`).  
- **KC_HTTP_PORT**: The HTTP port on which Keycloak runs.  
- **SMS_API_URL**: The base URL of the SMS API service.  
- **SMS_API_COUNTRY_PATTERN**: A regex pattern to match supported phone number country codes.
- **SMS_API_AUTH_USERNAME**: The basic auth username for the SMS API.
- **SMS_API_AUTH_PASSWORD**: The basic auth password for the SMS API.
- **OAUTH2_CLIENT_ID**: The client ID for OAuth2 authentication with the SMS provider.  
- **OAUTH2_CLIENT_SECRET**: The client secret for OAuth2 authentication with the SMS provider.  
- **OAUTH2_TOKEN_ENDPOINT**: The URL of the token endpoint for OAuth2 authentication (e.g., `http://token-mock:8080/token` for testing, or `http://your-auth-server/oauth/token` in production).


Configure these variables in your deployment (Docker, Kubernetes, etc.) as shown in the examples above.  

---

## 4. Architecture

The plugin is built using a scalable, modular architecture:

### Core Components

- **Service Layer:**
  - `PhoneAuthenticationService` - Unified business logic for both browser and API flows
  - `PhoneAuthSessionService` - Distributed session management (cluster-safe)
  - `SmsService` - SMS sending and phone number validation

- **REST API Layer:**
  - `PhoneAuthResourceProvider` - RESTful endpoints for mobile/API clients
  - `PhoneAuthResourceProviderFactory` - Keycloak SPI integration

- **Browser Authenticator Layer:**
  - `PhoneNumberGetNumber` - Collects user phone numbers
  - `PhoneNumberConfirmNumber` - Displays confirmed phone number
  - `PhoneNumberChooseUser` - Looks up or creates users
  - `PhoneNumberSendTan` - Sends OTP via SMS
  - `PhoneNumberValidateTan` - Validates OTP
  - `PhoneNumberUpdateUser` - Profile updates post-authentication

### Key Features

- **🔄 Dual-Mode Support**: Browser forms and REST API
- **📊 Horizontal Scalability**: Distributed session storage
- **🛡️ Security**: Rate limiting, session locking, IP tracking
- **🌍 Multi-Tenancy**: Realm-aware session management
- **⚡ Performance**: Stateless design with caching

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     Client Applications                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Browser    │  │  Mobile App  │  │     SPA      │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
└─────────┼──────────────────┼──────────────────┼─────────────┘
          │                  │                  │
          │ Form POST        │ REST API         │ REST API
          │                  │                  │
┌─────────▼──────────────────▼──────────────────▼─────────────┐
│                  Keycloak Server                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │            Phone Auth Plugin                          │  │
│  │  ┌─────────────────┐      ┌────────────────────┐     │  │
│  │  │   Browser       │      │   REST API         │     │  │
│  │  │   Authenticators│      │   Provider         │     │  │
│  │  └────────┬────────┘      └─────────┬──────────┘     │  │
│  │           │                          │                │  │
│  │           └──────────┬───────────────┘                │  │
│  │                      │                                │  │
│  │           ┌──────────▼──────────┐                     │  │
│  │           │ PhoneAuthentication │                     │  │
│  │           │      Service        │                     │  │
│  │           └──────────┬──────────┘                     │  │
│  │                      │                                │  │
│  │         ┌────────────┼────────────┐                   │  │
│  │         │            │            │                   │  │
│  │    ┌────▼────┐  ┌───▼────┐  ┌───▼────┐              │  │
│  │    │ Session │  │  SMS   │  │  User  │              │  │
│  │    │ Service │  │Service │  │Provider│              │  │
│  │    └─────────┘  └────────┘  └────────┘              │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────┐  ┌────────────────┐                    │
│  │  Infinispan    │  │    User DB     │                    │
│  │  Cache         │  │                │                    │
│  └────────────────┘  └────────────────┘                    │
└──────────────────────────────────────────────────────────────┘
          │
          │ HTTP POST
          │
┌─────────▼──────────┐
│   SMS Provider     │
│  (SMSCountry API)  │
└────────────────────┘
```

---

## 5. Contribute

We welcome contributions! Here’s how you can get involved:

### Reporting Issues

- Use the GitHub issues page to report bugs, suggest enhancements, or request features.
- Ensure your issue includes detailed reproduction steps and relevant logs.

### Contributing Code

- **Fork the Repository:**  
  Create a fork, then clone it locally.
- **Branching Model:**
    - Use feature branches for new features or fixes.
    - Ensure your branch name is descriptive (e.g., `feature/sms-service-refactor`).
- **Coding Standards:**  
  Follow the existing coding conventions.
    - Write unit tests for your changes.
    - Run `mvn clean install` and ensure all tests pass.
- **Pull Requests:**  
  Submit a pull request with a clear description of your changes, referencing any issues addressed.

### Documentation & Feedback

- Update documentation as needed with your changes.
- Provide feedback on areas for improvement or additional features.

---

*For more details or questions, please contact [dev@ssegning.com](mailto:dev@ssegning.com).*