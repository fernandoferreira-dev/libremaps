# LibreMaps

## Run the server locally

The MariaDB connection is currently configured directly in
`server/src/main/java/com/example/server/Database.kt`, so the server can be started with:

```bash
./gradlew run
```

The server creates the bookmark tables when it starts. Move the credentials back to
environment variables before sharing or deploying this repository.