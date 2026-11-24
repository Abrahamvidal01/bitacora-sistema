package com.sistema.bitacora.supervisorbitacora;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in de migracion V12. No es prueba funcional ni de restauracion. */
@EnabledIfEnvironmentVariable(named="BITACORA_BO3_APLICAR_V12", matches="SI_SOLO_CLON")
class PrepararBitacoraOperativaV12Test {
    private static final String URL="jdbc:mariadb://127.0.0.1:3308/bitacora_pruebas_confirmacion?connectionTimeZone=UTC";
    private static final String SCHEMA="bitacora_pruebas_confirmacion";
    private static final String DATADIR="c:/users/warri/rescate-bitacora/restauracion_limpia_20261103_130538_668/datos";
    private static final String V11="V11__preparacion_espacios_y_avisos_R2.sql";
    private static final String V12="V12__asignacion_supervisor_bitacora.sql";
    private static final Set<String> TABLAS_V11=Set.of("bitacora_operativa_eventos","bitacora_operativa_avisos");
    private static String required(String key){String v=System.getenv(key);require(v!=null&&!v.isBlank(),"Falta variable BO3: "+key);return v;}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static String path(String v){return v.replace('\\','/').replaceAll("/+$","").toLowerCase(Locale.ROOT);}
    private static void identity(Connection c)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@port,VERSION(),@@datadir,SUBSTRING_INDEX(CURRENT_USER(),'@',1),@@session.check_constraint_checks")){
            require(r.next(),"Sin identidad de servidor");
            require(SCHEMA.equals(r.getString(1))&&r.getInt(2)==3308&&r.getString(3).startsWith("10.4.32-MariaDB")
                &&DATADIR.equals(path(r.getString(4)))&&"bitacora_app".equals(r.getString(5))&&r.getBoolean(6),"Identidad/cuenta/CHECK incorrectos");
        }
    }
    private static void history(Connection c,int last)throws SQLException{
        Set<String> got=new HashSet<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT version,success FROM flyway_schema_history WHERE type<>'TABLE'")){
            while(r.next())require(r.getBoolean(2)&&r.getString(1)!=null&&got.add(r.getString(1)),"Historial inesperado");
        }
        Set<String> expected=new HashSet<>();for(int i=1;i<=last;i++)expected.add(Integer.toString(i));
        require(got.equals(expected),"Se requiere exclusivamente V1-V"+last+" correctas");
    }
    private static Set<String> tables(Connection c)throws SQLException{
        Set<String> got=new HashSet<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bitacora_operativa_eventos','bitacora_operativa_avisos','bitacora_operativa_asignaciones')")){
            while(r.next())got.add(r.getString(1));
        }return got;
    }
    private static void controls(Connection c)throws SQLException{
        List<String> got=new ArrayList<>();
        for(String t:List.of("nombramientos_departamento","espacios","contador_folios_reserva","departamentos","consumo_cupos_reserva","solicitud_recursos")){
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();got.add(Long.toString(r.getLong(1)));}
        }
        require(String.join(",",got).equals(required("BITACORA_BO3_CONTEOS")),"Conteos diferentes del respaldo");
    }
    private static void empty(Connection c,String table)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();require(r.getLong(1)==0,"Se esperaba tabla operativa vacia");}
    }
    private static void verifySql(Path stage,String file,String hashEnv)throws Exception{
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(stage.resolve(file))));
        require(digest.equalsIgnoreCase(required(hashEnv)),"Cambio el SQL revisado");
    }
    @Test void aplicarExclusivamenteV12AlClon()throws Exception{
        boolean migrationStarted=false;
        String fase="CONFIGURACION";
        try{
            require(URL.equals(required("BITACORA_BO3_URL")),"URL incorrecta");
            Path project=Path.of(required("BITACORA_BO3_PROYECTO")).toAbsolutePath().normalize();
            Path stage=Path.of(required("BITACORA_BO3_SQL_DIR")).toAbsolutePath().normalize();
            try(var list=Files.list(stage)){
                require(list.map(p->p.getFileName().toString()).sorted().toList().equals(List.of(V11,V12)),"Solo V11 historica y V12 pueden estar en el directorio SQL");
            }
            verifySql(stage,V11,"BITACORA_BO3_V11_SHA256");verifySql(stage,V12,"BITACORA_BO3_V12_SHA256");
            DataSource ds=new GuardedSource(required("BITACORA_BO3_PASSWORD"));
            fase="IDENTIDAD_HISTORIAL_Y_CONTEOS";
            try(Connection c=ds.getConnection()){
                history(c,11);controls(c);require(tables(c).equals(TABLAS_V11),"V11 incompleta o tabla V12 ya existente");
                for(String t:TABLAS_V11)empty(c,t);
            }
            fase="VALIDACION_FLYWAY";
            Flyway flyway=Flyway.configure().dataSource(ds)
                .locations("filesystem:"+project.resolve("src/main/resources/db/migration").toString().replace('\\','/'),
                           "filesystem:"+project.resolve("src/main/resources/db/supervisor").toString().replace('\\','/'),
                           "filesystem:"+stage.toString().replace('\\','/'))
                .failOnMissingLocations(true).target("12").cleanDisabled(true).baselineOnMigrate(false)
                .outOfOrder(false).validateOnMigrate(true).ignoreMigrationPatterns("*:pending").load();
            flyway.validate();
            MigrationInfo[] pending=flyway.info().pending();
            require(pending.length==1&&pending[0].getVersion()!=null&&"12".equals(pending[0].getVersion().toString())&&V12.equals(pending[0].getScript()),"Solo V12 puede estar pendiente");
            fase="REVALIDACION_PREVIA";
            try(Connection c=ds.getConnection()){
                history(c,11);controls(c);require(tables(c).equals(TABLAS_V11),"Cambio concurrente de tablas");
                for(String t:TABLAS_V11)empty(c,t);
            }
            fase="MIGRACION";migrationStarted=true;
            flyway.migrate();
            fase="VERIFICACION_POSTERIOR";
            flyway.validate();require(flyway.info().pending().length==0,"Pendientes inesperadas");
            try(Connection c=ds.getConnection()){
                history(c,12);controls(c);
                require(tables(c).equals(Set.of("bitacora_operativa_eventos","bitacora_operativa_avisos","bitacora_operativa_asignaciones")),"Tablas inesperadas");
                for(String t:tables(c))empty(c,t);
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bitacora_operativa_asignaciones'")){
                    require(r.next()&&"InnoDB".equalsIgnoreCase(r.getString(1)),"Motor incorrecto");
                }
                Set<String> checks=new HashSet<>();
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='bitacora_operativa_asignaciones' AND CONSTRAINT_TYPE='CHECK'")){
                    while(r.next())checks.add(r.getString(1));
                }
                require(checks.equals(Set.of("ck_bo_asignacion_clave","ck_bo_asignacion_firma")),"CHECK de asignacion ausentes/inesperados");
                List<String> fk=new ArrayList<>();
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_SCHEMA,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_bo_evento_asignacion' ORDER BY ORDINAL_POSITION")){
                    while(r.next())fk.add(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3)+"|"+r.getString(4)+"|"+r.getString(5));
                }
                require(fk.equals(List.of(
                    "bitacora_operativa_eventos|solicitud_id|"+SCHEMA+"|bitacora_operativa_asignaciones|solicitud_id",
                    "bitacora_operativa_eventos|supervisor_responsable_usuario_id|"+SCHEMA+"|bitacora_operativa_asignaciones|supervisor_usuario_id")),"FK de responsable no coincide");
            }
            System.out.println("V12 aplicada y validada: tabla de asignaciones vacia, dos CHECK presentes y FK de responsable verificada. Sin asignaciones, avisos o correo. No es prueba funcional ni de enforcement.");
        }catch(Exception ex){
            throw new IllegalStateException("BO3-V12 no confirmado en fase "+fase+". "+(migrationStarted
                ? "DDL puede haber quedado parcial. Conservar carpeta; no reintentar ni ejecutar repair."
                : "No se invoco migrate. Revisar prevalidacion sin modificar cuentas ni permisos."));
        }
    }
    private static final class GuardedSource implements DataSource{
        private final String password;
        GuardedSource(String password){this.password=password;}
        public Connection getConnection()throws SQLException{
            Connection c=DriverManager.getConnection(URL,"bitacora_app",password);
            try{identity(c);return c;}catch(RuntimeException|SQLException ex){c.close();throw new SQLException("Identidad BO3 no verificada");}
        }
        public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Credenciales alternativas no permitidas");}
        public PrintWriter getLogWriter(){return null;}
        public void setLogWriter(PrintWriter w)throws SQLException{if(w!=null)throw new SQLException("Registro JDBC no permitido");}
        public void setLoginTimeout(int seconds){}
        public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getLogger("bitacora.bo3");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("unwrap no admitido");}
        public boolean isWrapperFor(Class<?> type){return false;}
        public String toString(){return "BO3 DataSource [credenciales ocultas]";}
    }
}
