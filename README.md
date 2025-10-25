# Dropbox Assessment — CloudEagle

This repository contains:

- `TEMPLATE.md` — filled assessment template describing Dropbox Business APIs and Postman guidance
- `src/Main.java` — Java program demonstrating OAuth2 Authorization Code flow with Dropbox and team endpoints

Prerequisites:
- Java 11 or newer installed and on PATH.
- A Dropbox Business (Team) account and a Dropbox App created in the App Console (https://www.dropbox.com/developers/apps).

Setup:
1. Create an app in Dropbox App Console (Scoped access, Team if needed). Add redirect URI e.g. http://localhost:4567/oauth
2. Set environment variables in PowerShell:

```powershell
$env:DROPBOX_CLIENT_ID = "<your-client-id>"
$env:DROPBOX_CLIENT_SECRET = "<your-client-secret>"
$env:DROPBOX_REDIRECT_URI = "http://localhost:4567/oauth"
# or, if you already have an access token:
$env:DROPBOX_ACCESS_TOKEN = "<your-access-token>"
```

Run the Java example / Usage examples:

```powershell
# (prints message if env not set)
java -cp out Main team-info    # fetches team metadata (/2/team/get_info)
java -cp out Main members      # lists members (/2/team/members/list_v2)
java -cp out Main team-logs    # fetches team log events (/2/team_log/get_events)
```

Notes:
- If `DROPBOX_ACCESS_TOKEN` is set, the program uses it and skips the interactive browser auth.
- To run interactive OAuth, set `DROPBOX_CLIENT_ID`, `DROPBOX_CLIENT_SECRET`, and `DROPBOX_REDIRECT_URI` and run the program; it will open the browser to authorize and capture the authorization code.

