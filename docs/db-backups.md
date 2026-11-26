# Moniewise Database Backups

Use `scripts\Backup-Database.ps1` to create a timestamped PostgreSQL dump on your PC.

By default, backups are saved to:

```powershell
$HOME\Downloads\MoniewiseDbBackups
```

## Requirements

Install PostgreSQL client tools so `pg_dump` is available:

```powershell
pg_dump --version
```

## Credentials

The script reads the same database variables used by the Spring app:

```dotenv
DATABASE_URL=jdbc:postgresql://localhost:5432/moniewise_db
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=change_me_locally
```

You can keep those values in `src\main\resources\.env`; that file is ignored by git.

## Run One Backup

```powershell
.\scripts\Backup-Database.ps1
```

To choose another folder:

```powershell
.\scripts\Backup-Database.ps1 -OutputDirectory "$HOME\Downloads\MoniewiseDbBackups"
```

## Auto Backup Daily

Install a Windows Scheduled Task:

```powershell
.\scripts\Install-DatabaseBackupTask.ps1 -At 02:30
```

The task runs once per day and keeps the latest backups on your PC. Old `moniewise-db-*.dump` and `moniewise-db-*.sql` files in the backup folder are deleted after 14 days by default.

## Restore A Custom Dump

```powershell
pg_restore --clean --if-exists --dbname "postgresql://USER@HOST:5432/DB_NAME" "$HOME\Downloads\MoniewiseDbBackups\moniewise-db-YYYYMMDD-HHMMSS.dump"
```
