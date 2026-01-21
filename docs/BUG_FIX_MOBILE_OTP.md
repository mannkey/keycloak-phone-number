# Bug Fix: Mobile App OTP Validation Error

## Issue Description

Mobile app getting `invalid_code` error during OTP validation in production.

### Error Symptoms
```
ERROR  ❌ Form validation error: invalid_code
ERROR  OTP validation error: [Error: invalid_code]
```

### Root Causes Identified

## 🐛 Bug #1: Incorrect Form Field Name

**Problem:** Mobile app sends OTP with field name `tan` but server expects `code`

**Mobile App Sends:**
```
tan=521141&validate-tan=true
```

**Server Expects:**
```
code=521141
```

**Impact:** OTP validation always fails because the code value is never received by the server.

---

## 🐛 Bug #2: Incorrect Phone Number Parsing

**Problem:** Mobile app incorrectly splits the phone number

**From Logs:**
```
📞 Normalized phone: +917207207260  ← Correct full number
🌍 Region prefix: +917               ← WRONG! Should be +91
📱 Phone without prefix: 207207260   ← WRONG! Should be 7207207260
```

**Correct Values:**
- Region Prefix: `+91`
- Phone: `7207207260`
- Full Number: `+917207207260`

**Impact:** Phone number sent to server doesn't match the number that received SMS.

---

## ✅ Fixes Applied

### Fix #1: Accept Both Field Names (Server-Side)

Updated `PhoneNumberValidateTan.java` to accept both `code` and `tan` for backward compatibility:

```java
// Accept both 'code' and 'tan' for backward compatibility with mobile apps
String code = formData.getFirst("code");
if (code == null || code.isEmpty()) {
    code = formData.getFirst("tan");
}

// Validate that code is provided
if (code == null || code.trim().isEmpty()) {
    log.warnf("No OTP code provided in form data");
    // Show appropriate error
}
```

**Benefits:**
- ✅ Works with both old and new mobile app versions
- ✅ Better error logging
- ✅ Validates input properly

### Fix #2: Enhanced Logging

Added detailed logging in `PhoneNumberGetNumber.java`:

```java
log.debugf("Phone auth: regionPrefix='%s', phoneWithoutPrefix='%s', fullNumber='%s'",
          regionPrefix, phoneNumberWithoutPrefix, phoneNumber);
```

This will help debug phone number parsing issues in production.

---

## 📱 Mobile App Fixes Required

### Priority 1: Fix Form Field Name (Critical)

**Current Code (WRONG):**
```javascript
const formData = `tan=${otp}&validate-tan=true`;
```

**Fixed Code:**
```javascript
const formData = `code=${otp}`;
```

**File to Update:** Your mobile app's OTP validation code

---

### Priority 2: Fix Phone Number Parsing (Critical)

**Current Logic (WRONG):**
```javascript
// Example of incorrect parsing
const normalized = "+917207207260";
const regionPrefix = normalized.substring(0, 4); // "+917" ❌
const phone = normalized.substring(4);           // "207207260" ❌
```

**Correct Logic:**
```javascript
// Method 1: Use known country codes
const regionPrefix = "+91";  // For India
const phone = normalized.startsWith("+91") 
    ? normalized.substring(3)  // Remove +91
    : normalized;

// Method 2: Use a phone number library
import { parsePhoneNumber } from 'libphonenumber-js';

const parsed = parsePhoneNumber("+917207207260");
const regionPrefix = `+${parsed.countryCallingCode}`;  // "+91"
const phone = parsed.nationalNumber;                    // "7207207260"
```

**Send to Server:**
```javascript
const formData = `regionPrefix=${encodeURIComponent(regionPrefix)}&phone=${encodeURIComponent(phone)}`;

// Should produce: regionPrefix=%2B91&phone=7207207260
```

---

## 🔍 Debugging Your Mobile App

### Step 1: Check What You're Sending

Add logging before sending OTP:

```javascript
console.log('🔍 Debug OTP Submission:');
console.log('  Full Phone Number:', fullPhoneNumber);
console.log('  Region Prefix:', regionPrefix);
console.log('  Phone without prefix:', phoneWithoutPrefix);
console.log('  OTP Code:', otp);
console.log('  Form Data:', formData);
```

### Step 2: Verify Server Response

The server should parse correctly:
```
✅ regionPrefix=+91
✅ phone=7207207260
✅ Full number sent to SMS: +917207207260
```

### Step 3: Verify Form Field Name

When submitting OTP, use:
```javascript
// ✅ CORRECT
code=${otp}

// ❌ WRONG
tan=${otp}
```

---

## 📊 Testing Checklist

### Before Deployment

- [ ] Phone number parsing produces correct regionPrefix (+91 for India)
- [ ] Phone number parsing produces correct phone (7207207260 for your example)
- [ ] OTP form submits with field name `code` (not `tan`)
- [ ] OTP validation succeeds with correct 6-digit code
- [ ] Error handling shows appropriate messages

### Test Cases

**Test 1: Indian Number**
```
Input: +917207207260
Expected Output:
  regionPrefix: +91
  phone: 7207207260
```

**Test 2: US Number**
```
Input: +14155552671
Expected Output:
  regionPrefix: +1
  phone: 4155552671
```

**Test 3: OTP Submission**
```
Form Data: code=123456
Expected: Success if OTP matches
```

---

## 🚀 Deployment Steps

### 1. Deploy Server Fix

```bash
# Build the updated plugin
mvn clean package -DskipTests

# Copy JAR to Keycloak
cp target/keycloak-phonenumber-login-*.jar /opt/keycloak/providers/

# Restart Keycloak
systemctl restart keycloak
```

### 2. Update Mobile App

Update these files in your mobile app:
1. Phone number parsing logic
2. OTP form submission logic
3. Add debug logging

### 3. Test in Staging

1. Test with Indian number (+91...)
2. Test OTP reception
3. Test OTP validation
4. Check server logs for correct parsing

### 4. Deploy to Production

Once staging tests pass:
1. Deploy server changes
2. Deploy mobile app update
3. Monitor error rates
4. Check logs for any parsing issues

---

## 📋 Server Configuration Check

Ensure these environment variables are set correctly:

```bash
# SMS API Configuration
SMS_API_URL=https://restapi.smscountry.com/v0.1
SMS_API_AUTH_USERNAME=your-username
SMS_API_AUTH_PASSWORD=your-password

# Country Support (India)
SMS_API_COUNTRY_PATTERN=in|IN|.*

# Phone Attribute Name
SMS_API_ATTRIBUTE_PHONE_NAME=phoneNumber
```

---

## 🔧 Alternative: Use the New REST API

Instead of fixing the browser flow, you could switch to the new REST API:

### Advantages
- No HTML form parsing needed
- Direct JSON responses
- Better error messages
- Easier to debug

### Implementation

```javascript
// 1. Request OTP
const response = await fetch('https://auth.iheal.digital/realms/iHeal/phone-auth/request-otp', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    phone: '7207207260',
    regionPrefix: '+91',
    clientId: 'iHealMobileApp'
  })
});

const { sessionId, phoneNumber, expiresIn } = await response.json();

// 2. Verify OTP
const verifyResponse = await fetch('https://auth.iheal.digital/realms/iHeal/phone-auth/verify-otp', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    sessionId: sessionId,
    code: '521141'
  })
});

const { accessToken, refreshToken } = await verifyResponse.json();
```

See `docs/API_DOCUMENTATION.md` for complete details.

---

## 📞 Support

If issues persist after applying these fixes:

1. **Check Server Logs:**
   ```bash
   tail -f /opt/keycloak/logs/keycloak.log | grep -i "phone"
   ```

2. **Enable Debug Logging:**
   Add to `keycloak.conf`:
   ```
   log-level=DEBUG,com.vymalo.keycloak:TRACE
   ```

3. **Contact Support:**
   - Repository: https://github.com/vymalo/keycloak-phone-number
   - Email: dev@ssegning.com
   - Include: Server logs, mobile app logs, phone number used (sanitized)

---

## 📝 Summary

**Critical Fixes Required in Mobile App:**

1. ✅ Change `tan` to `code` in OTP form submission
2. ✅ Fix phone number parsing to split correctly (+91 vs +917)
3. ✅ Add proper URL encoding for form data
4. ✅ Add debug logging

**Server Fixes Applied:**

1. ✅ Accept both `tan` and `code` field names (backward compatible)
2. ✅ Enhanced logging for debugging
3. ✅ Better error messages

**Next Steps:**

1. Update mobile app code
2. Test in staging environment
3. Deploy to production
4. Monitor logs for successful OTP validations

The server-side fixes are backward compatible and ready for deployment. The mobile app must be updated to work correctly.
