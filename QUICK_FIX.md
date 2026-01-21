# Quick Fix Guide - Mobile OTP Error

## Problem
Mobile app getting `invalid_code` error during OTP validation.

## Root Cause
Mobile app sends `tan=123456` but server expects `code=123456`

## Fix (Choose One)

### Option 1: Fix Mobile App (Recommended)
Change your OTP submission code:

```javascript
// BEFORE (Wrong)
const formData = `tan=${otp}&validate-tan=true`;

// AFTER (Correct)
const formData = `code=${otp}`;
```

### Option 2: Deploy Server Update (Immediate Fix)
I've updated the server to accept both `tan` and `code`.

Deploy the updated plugin:
```bash
mvn clean package
cp target/keycloak-phonenumber-login-*.jar /opt/keycloak/providers/
systemctl restart keycloak
```

### Option 3: Use New REST API (Best Long-term)
Switch to the REST API instead of browser flow:

```javascript
// 1. Request OTP
const { sessionId } = await fetch('/realms/iHeal/phone-auth/request-otp', {
  method: 'POST',
  body: JSON.stringify({ phone: '7207207260', regionPrefix: '+91' })
}).then(r => r.json());

// 2. Verify OTP
const { accessToken } = await fetch('/realms/iHeal/phone-auth/verify-otp', {
  method: 'POST',
  body: JSON.stringify({ sessionId, code: '521141' })
}).then(r => r.json());
```

## Additional Issue: Phone Number Parsing

Your mobile app is splitting the phone number incorrectly:
```
❌ regionPrefix: +917 (WRONG)
✅ regionPrefix: +91  (CORRECT)
```

Fix your phone parsing logic to use proper country code (+91 for India).

## For India
- Country Code: **+91**
- Example: 7207207260
- Full Number: +917207207260

---

See `docs/BUG_FIX_MOBILE_OTP.md` for complete details.
