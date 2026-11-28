package com.sistema.bitacora.supervisordepartamentos;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Herramienta opt-in estructural. Sin Spring, sin datos de prueba y sin rollback DDL. */
@EnabledIfEnvironmentVariable(named="BITACORA_DEP_V13_R2_APLICAR", matches="SI_SOLO_CLON")
class PrepararHistorialDepartamentosV13R2Test {
    private static final String PREFIX="BITACORA_DEP_V13_R2_";
    private static final String URL="jdbc:mariadb://127.0.0.1:3308/bitacora_pruebas_confirmacion?connectionTimeZone=UTC";
    private static final String SCHEMA="bitacora_pruebas_confirmacion";
    private static final String PROJECT="C:/Users/warri/IdeaProjects/bitacora-sistema-limpio";
    private static final String STAGE="C:/Users/warri/rescate-bitacora/revision-historial-departamentos-v13/preparacion-v13-r2/sql";
    private static final String DATADIR="c:/users/warri/rescate-bitacora/restauracion_limpia_20261103_130538_668/datos";
    private static final String TABLE="historial_departamentos";
    private static final String HISTORY="flyway_schema_history";
    private static final String V11="V11__preparacion_espacios_y_avisos_R2.sql";
    private static final String V12="V12__asignacion_supervisor_bitacora.sql";
    private static final String V13="V13__historial_departamentos.sql";
    private static final List<String> SIX=List.of("nombramientos_departamento","espacios","contador_folios_reserva","departamentos","consumo_cupos_reserva","solicitud_recursos");
    private static void require(boolean b,String safe){if(!b)throw new IllegalStateException(safe);}
    private static String env(String key){String v=System.getenv(PREFIX+key);require(v!=null&&!v.isBlank(),"Falta parametro requerido");return v;}
    private static String norm(String p){return p.replace((char)92,'/').replaceAll("/+$","").toLowerCase(Locale.ROOT);}
    private static String quote(String t){require(t.matches("[A-Za-z0-9_]+"),"Identificador local no admitido");return (char)96+t+String.valueOf((char)96);}
    private static Path checked(Path p)throws Exception{
        p=p.toAbsolutePath().normalize();
        for(Path a=p;a!=null;a=a.getParent())require(!Files.isSymbolicLink(a),"Enlace no permitido");
        require(norm(p.toString()).equals(norm(p.toRealPath().toString())),"Ruta real diferente");
        return p;
    }
    private static void verifyFiles(Path project,Path stage)throws Exception{
        require(norm(checked(project).toString()).equals(norm(PROJECT)),"Proyecto no permitido");
        require(norm(checked(stage).toString()).equals(norm(STAGE))&&!stage.startsWith(project),"Stage no permitido");
        try(var files=Files.list(stage)){
            var list=files.toList();
            require(list.size()==3,"Stage debe contener tres archivos");
            Set<String> names=new TreeSet<>();
            for(Path p:list){checked(p);require(Files.isRegularFile(p),"Stage con entrada no regular");names.add(p.getFileName().toString());}
            require(names.equals(Set.of(V11,V12,V13)),"Stage contiene SQL inesperado");
        }
        for(var entry:Map.of(V11,"SQL11_SHA256",V12,"SQL12_SHA256",V13,"SQL13_SHA256").entrySet()){
            String expected=env(entry.getValue());require(expected.matches("[a-fA-F0-9]{64}"),"Hash no valido");
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(checked(stage.resolve(entry.getKey())))));
            require(actual.equalsIgnoreCase(expected),"Cambio un SQL revisado");
        }
        for(String root:List.of("src/main/resources/db/migration","src/main/resources/db/supervisor")){
            Path dir=checked(project.resolve(root));require(Files.isDirectory(dir),"Falta raiz historica");
            try(var walk=Files.walk(dir)){for(Path p:walk.toList())checked(p);}
        }
    }
    private static void identity(Connection c)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@port,VERSION(),@@datadir,SUBSTRING_INDEX(CURRENT_USER(),'@',1),@@session.check_constraint_checks,@@session.foreign_key_checks")){
            require(r.next()&&SCHEMA.equals(r.getString(1))&&r.getInt(2)==3308
                &&r.getString(3).startsWith("10.4.32-MariaDB")&&DATADIR.equals(norm(r.getString(4)))
                &&"bitacora_app".equals(r.getString(5))&&r.getInt(6)==1&&r.getInt(7)==1,"Identidad o comprobaciones de sesion incorrectas");
        }
    }
    private static void history(Connection c,int last)throws SQLException{
        Set<Integer> versions=new HashSet<>();int count=0;
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT version,type,script,success FROM flyway_schema_history ORDER BY installed_rank")){
            while(r.next()){
                String v=r.getString(1);require(v!=null&&v.matches("[1-9][0-9]*"),"Version historica no valida");
                int n=Integer.parseInt(v);require(n>=1&&n<=last&&versions.add(n),"Version duplicada o no esperada");
                require(r.getInt(4)==1&&!r.wasNull(),"Historial fallido o sin exito exacto");
                String type=r.getString(2),script=r.getString(3);
                if(n==1){
                    require("BASELINE".equals(type)&&"<< Flyway Baseline >>".equals(script),"V1 BASELINE no coincide exactamente");
                }else{
                    require("SQL".equals(type),"Version posterior a V1 no SQL");
                    require(script!=null&&script.startsWith("V"+n+"__")&&script.length()>("V"+n+"__").length()+4&&script.endsWith(".sql"),"Script historico inesperado");
                }
                if(n==11)require(V11.equals(script),"V11 no coincide");
                if(n==12)require(V12.equals(script),"V12 no coincide");
                if(n==13)require(V13.equals(script),"V13 no coincide");
                count++;
            }
        }
        require(count==last&&versions.size()==last,"Se requiere historial exacto y consecutivo");
    }
    private static SortedSet<String> tables(Connection c)throws SQLException{
        SortedSet<String> out=new TreeSet<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            while(r.next()){String t=r.getString(1);quote(t);require(out.add(t),"Tabla duplicada");}
        }return out;
    }
    private static long count(Connection c,String t)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+quote(t))){require(r.next(),"Sin conteo");return r.getLong(1);}
    }
    private record Snapshot(SortedMap<String,Long> counts,SortedMap<String,String> ddl){}
    private static Snapshot snapshot(Connection c)throws SQLException{
        SortedMap<String,Long> counts=new TreeMap<>();SortedMap<String,String> ddl=new TreeMap<>();
        for(String t:tables(c)){
            counts.put(t,count(c,t));
            if(!HISTORY.equals(t))try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW CREATE TABLE "+quote(t))){require(r.next(),"Sin DDL local");ddl.put(t,r.getString(2));}
        }
        require(counts.containsKey(HISTORY),"Falta historial Flyway");return new Snapshot(counts,ddl);
    }
    private static void controls(Connection c)throws SQLException{
        List<String> actual=new ArrayList<>();for(String t:SIX)actual.add(Long.toString(count(c,t)));
        String wanted=env("CONTEOS6");require(wanted.matches("[0-9]+(,[0-9]+){5}")&&String.join(",",actual).equals(wanted),"Conteos de respaldo no coinciden");
    }
    private static void parents(Connection c)throws SQLException{
        for(String t:List.of("departamentos","usuarios")){
            try(PreparedStatement s=c.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND TABLE_TYPE='BASE TABLE'")){
                s.setString(1,t);try(ResultSet r=s.executeQuery()){require(r.next()&&"InnoDB".equalsIgnoreCase(r.getString(1))&&!r.next(),"Motor de padre no valido");}
            }
            try(PreparedStatement s=c.prepareStatement("SELECT DATA_TYPE,COLUMN_TYPE,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='id'")){
                s.setString(1,t);try(ResultSet r=s.executeQuery()){require(r.next()&&"bigint".equalsIgnoreCase(r.getString(1))&&!r.getString(2).toLowerCase(Locale.ROOT).contains("unsigned")&&"NO".equals(r.getString(3))&&!r.next(),"id de padre incompatible");}
            }
            List<String> pk=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")){
                s.setString(1,t);try(ResultSet r=s.executeQuery()){while(r.next())pk.add(r.getString(1));}
            }require(pk.equals(List.of("id")),"PK de padre incompatible");
        }
        // Columna exacta establecida por V7. Sin descubrimiento ni SQL adaptativo.
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='departamentos' AND COLUMN_NAME='cupo_mensual'")){
            require(r.next()&&"tinyint".equalsIgnoreCase(r.getString(1))
                &&r.getString(2).toLowerCase(Locale.ROOT).contains("unsigned")
                &&"NO".equals(r.getString(3))&&"1".equals(r.getString(4))&&!r.next(),"cupo_mensual no coincide con V7");
        }
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM departamentos WHERE cupo_mensual IS NULL OR cupo_mensual NOT IN (1,4)")){
            require(r.next()&&r.getLong(1)==0,"Cupos fuera de 1 o 4: detener sin corregir datos");
        }
    }
    private static Flyway flyway(DataSource ds,Path project,Path stage,boolean allowPending){
        var config=Flyway.configure().dataSource(ds).defaultSchema(SCHEMA).schemas(SCHEMA).createSchemas(false)
            .locations("filesystem:"+project.resolve("src/main/resources/db/migration").toString().replace((char)92,'/'),
                       "filesystem:"+project.resolve("src/main/resources/db/supervisor").toString().replace((char)92,'/'),
                       "filesystem:"+stage.toString().replace((char)92,'/'))
            .failOnMissingLocations(true).target("13").cleanDisabled(true).baselineOnMigrate(false)
            .outOfOrder(false).validateOnMigrate(true);
        if(allowPending)config.ignoreMigrationPatterns("*:pending");else config.ignoreMigrationPatterns(new String[0]);
        return config.load();
    }
    private static void pending13(Flyway f){
        MigrationInfo[] p=f.info().pending();
        require(p.length==1&&p[0].getVersion()!=null&&"13".equals(p[0].getVersion().toString())&&V13.equals(p[0].getScript()),"Debe estar pendiente solo V13");
    }
    private static void newTable(Connection c)throws SQLException{
        require(count(c,TABLE)==0,"V13 no esta vacia");
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='historial_departamentos'")){
            require(r.next()&&"InnoDB".equalsIgnoreCase(r.getString(1))&&"utf8mb4_general_ci".equals(r.getString(2))&&!r.next(),"Motor/collation V13 incorrecto");
        }
        List<String> columns=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,EXTRA,CHARACTER_MAXIMUM_LENGTH,DATETIME_PRECISION,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='historial_departamentos' ORDER BY ORDINAL_POSITION")){
            while(r.next()){
                String type=r.getString(2).toLowerCase(Locale.ROOT);
                String detail=type.equals("varchar")?"("+r.getLong(6)+")":type.equals("datetime")?"("+r.getInt(7)+")":"";
                String unsigned=r.getString(3).toLowerCase(Locale.ROOT).contains("unsigned")?" unsigned":"";
                if(type.equals("varchar"))require("utf8mb4_general_ci".equals(r.getString(8)),"Collation de columna incorrecto");
                columns.add(r.getString(1)+"|"+type+detail+unsigned+"|"+r.getString(4)+"|"+r.getString(5));
            }
        }
        require(columns.equals(List.of("id|bigint|NO|auto_increment","departamento_id|bigint|NO|","realizado_por_usuario_id|bigint|NO|","tipo_accion|varchar(32)|NO|","nombre_anterior|varchar(100)|YES|","nombre_nuevo|varchar(100)|NO|","clave_anterior|varchar(20)|YES|","clave_nueva|varchar(20)|YES|","activo_anterior|tinyint|YES|","activo_nuevo|tinyint|NO|","cupo_anterior|tinyint unsigned|YES|","cupo_nuevo|tinyint unsigned|NO|","fecha_evento_utc|datetime(6)|NO|")),"Columnas V13 incorrectas");
        List<String> indexes=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='historial_departamentos' ORDER BY BINARY INDEX_NAME,SEQ_IN_INDEX")){
            while(r.next())indexes.add(r.getString(1)+"|"+r.getInt(2)+"|"+r.getInt(3)+"|"+r.getString(4)+"|"+r.getString(5));
        }
        require(indexes.equals(List.of("PRIMARY|0|1|id|BTREE","ix_histdep_departamento_id|1|1|departamento_id|BTREE","ix_histdep_departamento_id|1|2|id|BTREE","ix_histdep_usuario_id|1|1|realizado_por_usuario_id|BTREE","ix_histdep_usuario_id|1|2|id|BTREE")),"PK/indices V13 incorrectos");
        List<String> fks=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT k.CONSTRAINT_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_SCHEMA,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.UPDATE_RULE,r.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE k.CONSTRAINT_SCHEMA=DATABASE() AND k.TABLE_NAME='historial_departamentos' ORDER BY k.CONSTRAINT_NAME,k.ORDINAL_POSITION")){
            while(r.next())fks.add(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3)+"|"+r.getString(4)+"|"+r.getString(5)+"|"+r.getString(6)+"|"+r.getString(7));
        }
        require(fks.equals(List.of("fk_histdep_departamento|departamento_id|"+SCHEMA+"|departamentos|id|RESTRICT|RESTRICT","fk_histdep_usuario|realizado_por_usuario_id|"+SCHEMA+"|usuarios|id|RESTRICT|RESTRICT")),"FK V13 incorrectas");
        Set<String> checks=new HashSet<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='historial_departamentos' AND CONSTRAINT_TYPE='CHECK'")){
            while(r.next())require(checks.add(r.getString(1)),"CHECK duplicado");
        }
        require(checks.equals(Set.of("ck_histdep_accion","ck_histdep_nombre","ck_histdep_activo_ant","ck_histdep_activo_nuevo","ck_histdep_cupo_ant","ck_histdep_cupo_nuevo")),"CHECK V13 incorrectos");
    }
    private static void muteJdbcDiagnostics()throws Exception{
        DriverManager.setLogWriter(null);DriverManager.setLoginTimeout(8);
        Logger jul=Logger.getLogger("org.mariadb.jdbc");jul.setLevel(Level.OFF);jul.setUseParentHandlers(false);
        // El proyecto Boot usa SLF4J/Logback. Si cambia el backend, detener antes de conectar.
        Class<?> factory=Class.forName("org.slf4j.LoggerFactory");
        Object logger=factory.getMethod("getLogger",String.class).invoke(null,"org.mariadb.jdbc");
        Class<?> loggerClass=Class.forName("ch.qos.logback.classic.Logger");
        Class<?> levelClass=Class.forName("ch.qos.logback.classic.Level");
        require(loggerClass.isInstance(logger),"Backend de registro requiere revision");
        loggerClass.getMethod("setAdditive",boolean.class).invoke(logger,false);
        loggerClass.getMethod("setLevel",levelClass).invoke(logger,levelClass.getField("OFF").get(null));
    }
    @Test void aplicarExclusivamenteV13AlClon(){
        String phase="CONFIGURACION";boolean started=false;
        try{
            require(URL.equals(env("URL")),"URL no permitida");
            Path project=Path.of(env("PROYECTO")).toAbsolutePath().normalize();
            Path stage=Path.of(env("SQL_DIR")).toAbsolutePath().normalize();
            verifyFiles(project,stage);muteJdbcDiagnostics();
            DataSource ds=new GuardedSource(env("PASSWORD"));
            Snapshot before;
            phase="IDENTIDAD";
            try(Connection c=ds.getConnection()){
                phase="HISTORIAL";history(c,12);
                phase="INSTANTANEA_EXISTENTE";require(!tables(c).contains(TABLE),"V13 ya existe: no reaplicar");
                phase="PADRES";parents(c);
                phase="CONTEOS_RESPALDO";controls(c);
                phase="INSTANTANEA_EXISTENTE";before=snapshot(c);
            }
            phase="VALIDACION_FLYWAY";
            // Permitir pending en validate/migrate NO sustituye la guarda exacta V13.
            Flyway runner=flyway(ds,project,stage,true);runner.validate();pending13(runner);
            phase="REVALIDACION_PREVIA";verifyFiles(project,stage);pending13(runner);
            phase="IDENTIDAD";
            try(Connection c=ds.getConnection()){
                phase="HISTORIAL";history(c,12);
                phase="INSTANTANEA_EXISTENTE";require(!tables(c).contains(TABLE),"V13 aparecio concurrentemente");
                phase="PADRES";parents(c);
                phase="CONTEOS_RESPALDO";controls(c);
                phase="INSTANTANEA_EXISTENTE";require(before.equals(snapshot(c)),"Cambio concurrente de conteos o estructura");
            }
            // El operador mantiene todos los otros escritores detenidos; no es un bloqueo global.
            phase="MIGRACION";started=true;
            var result=runner.migrate();require(result.migrationsExecuted==1,"Debe ejecutarse exactamente una migracion");
            phase="VERIFICACION_POSTERIOR";
            // Nueva configuracion estricta: ningun patron de migraciones ignoradas.
            Flyway post=flyway(ds,project,stage,false);
            post.validate();require(post.info().pending().length==0,"Pendientes posteriores inesperadas");
            phase="IDENTIDAD";
            try(Connection c=ds.getConnection()){
                phase="HISTORIAL";history(c,13);
                phase="CONTEOS_RESPALDO";controls(c);
                phase="PADRES";parents(c);
                phase="VERIFICACION_V13";newTable(c);
                phase="INSTANTANEA_EXISTENTE";Snapshot after=snapshot(c);
                SortedMap<String,Long> expectedCounts=new TreeMap<>(before.counts());
                expectedCounts.put(HISTORY,Math.addExact(expectedCounts.get(HISTORY),1));expectedCounts.put(TABLE,0L);
                require(after.counts().equals(expectedCounts),"Tablas o conteos anteriores cambiaron");
                SortedMap<String,String> oldDdl=new TreeMap<>(after.ddl());oldDdl.remove(TABLE);
                require(oldDdl.equals(before.ddl()),"DDL previo cambio");
            }
            System.out.println("V13 estructural confirmada: una tabla nueva vacia; historial V1 BASELINE y V2-V13 SQL; conteos y DDL anteriores conservados. No prueba igualdad de filas, restauracion ni enforcement mediante escrituras.");
        }catch(Exception|LinkageError ex){
            // Sin causa, excepcion original, SQL, credenciales, variables ni salida del driver.
            throw new IllegalStateException("V13 no confirmada en fase "+phase+". "+(started
                ?"DDL/historial pueden haber quedado parciales. Conservar evidencias locales; NO reintentar, repair, baseline ni limpiar."
                :"No se invoco migrate. Revisar precondiciones sin cambiar datos, cuentas ni permisos."));
        }
    }
    private static final class GuardedSource implements DataSource{
        private final String password;
        GuardedSource(String p){require(p!=null&&!p.isBlank(),"Clave requerida");password=p;}
        public Connection getConnection()throws SQLException{
            Connection c=null;
            try{c=DriverManager.getConnection(URL,"bitacora_app",password);identity(c);return c;}
            catch(Exception ex){if(c!=null)try{c.close();}catch(Exception ignored){}throw new SQLException("Conexion del clon no autorizada o no disponible");}
        }
        public Connection getConnection(String u,String p)throws SQLException{
            if(!"bitacora_app".equals(u)||!password.equals(p))throw new SQLException("Credenciales alternativas no permitidas");
            return getConnection();
        }
        public PrintWriter getLogWriter(){return null;}
        public void setLogWriter(PrintWriter p)throws SQLException{if(p!=null)throw new SQLException("Registro JDBC no permitido");}
        public void setLoginTimeout(int seconds){}
        public int getLoginTimeout(){return 8;}
        public Logger getParentLogger(){Logger l=Logger.getLogger("bitacora.dep.v13.guard");l.setLevel(Level.OFF);l.setUseParentHandlers(false);return l;}
        public <T>T unwrap(Class<T> t)throws SQLException{throw new SQLException("unwrap no admitido");}
        public boolean isWrapperFor(Class<?> t){return false;}
        public String toString(){return "V13 GuardedSource [oculto]";}
    }
}
