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

/** Herramienta opt-in de migracion. NO es prueba funcional ni restauracion de respaldo. */
@EnabledIfEnvironmentVariable(named="BITACORA_BO2_APLICAR_V11", matches="SI_SOLO_CLON")
class PrepararBitacoraOperativaV11Test {
    private static final String URL = "jdbc:mariadb://127.0.0.1:3308/bitacora_pruebas_confirmacion?connectionTimeZone=UTC";
    private static final String SCHEMA = "bitacora_pruebas_confirmacion";
    private static final String DATADIR = "c:/users/warri/rescate-bitacora/restauracion_limpia_20261103_130538_668/datos";
    private static final String SCRIPT = "V11__preparacion_espacios_y_avisos_R2.sql";
    private static final Set<String> CHECKS = Set.of(
        "ck_bo_evento_revision","ck_bo_evento_accion","ck_bo_evento_actor","ck_bo_evento_clave",
        "ck_bo_evento_firma","ck_bo_evento_condiciones","ck_bo_evento_materiales",
        "ck_bo_evento_externos","ck_bo_evento_observaciones","ck_bo_aviso_texto",
        "ck_bo_aviso_intentos","ck_bo_aviso_canal_estado","ck_bo_aviso_lectura","ck_bo_aviso_envio");
    private static String required(String key) {
        String v=System.getenv(key);
        require(v!=null && !v.isBlank(),"Falta variable BO2 requerida: "+key); return v;
    }
    private static void require(boolean ok,String message) { if(!ok) throw new IllegalStateException(message); }
    private static String path(String v) { return v.replace('\\','/').replaceAll("/+$","").toLowerCase(Locale.ROOT); }
    private static void identity(Connection c) throws SQLException {
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery(
            "SELECT DATABASE(),@@port,VERSION(),@@datadir,SUBSTRING_INDEX(CURRENT_USER(),'@',1),@@session.check_constraint_checks")) {
            require(r.next(),"No se pudo identificar el servidor");
            require(SCHEMA.equals(r.getString(1)) && r.getInt(2)==3308 && r.getString(3).startsWith("10.4.32-MariaDB")
                && DATADIR.equals(path(r.getString(4))) && "bitacora_app".equals(r.getString(5))
                && r.getBoolean(6),"Identidad/cuenta/CHECK distintos del clon autorizado");
        }
    }
    private static void history(Connection c,int last) throws SQLException {
        Set<String> got=new HashSet<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT version,success FROM flyway_schema_history WHERE type<>'TABLE'")) {
            while(r.next()) require(r.getBoolean(2) && r.getString(1)!=null && got.add(r.getString(1)),"Historial fallido, repetido o inesperado");
        }
        Set<String> expected=new HashSet<>();for(int i=1;i<=last;i++)expected.add(Integer.toString(i));
        require(got.equals(expected),"Se requieren exclusivamente V1-V"+last+" correctas; no se reparara el historial");
    }
    private static Set<String> tables(Connection c) throws SQLException {
        Set<String> got=new HashSet<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bitacora_operativa_eventos','bitacora_operativa_avisos')")) {
            while(r.next())got.add(r.getString(1));
        }return got;
    }
    private static void controls(Connection c) throws SQLException {
        String expected=required("BITACORA_BO2_CONTEOS");
        List<String> values=new ArrayList<>();
        for(String t:List.of("nombramientos_departamento","espacios","contador_folios_reserva","departamentos","consumo_cupos_reserva","solicitud_recursos")) {
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();values.add(Long.toString(r.getLong(1)));}
        }
        require(String.join(",",values).equals(expected),"Conteos cambiaron desde el respaldo; no se aplicara V11");
    }
    @Test void aplicarExclusivamenteV11AlClon() throws Exception {
        boolean migrationStarted=false;
        try {
            require(URL.equals(required("BITACORA_BO2_URL")),"URL no permitida");
            Path project=Path.of(required("BITACORA_BO2_PROYECTO")).toAbsolutePath().normalize();
            Path stage=Path.of(required("BITACORA_BO2_SQL_DIR")).toAbsolutePath().normalize();
            try(var list=Files.list(stage)) { require(list.map(p->p.getFileName().toString()).toList().equals(List.of(SCRIPT)),"La carpeta SQL debe contener solo V11 R2"); }
            Path sql=stage.resolve(SCRIPT);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sql)));
            require(digest.equalsIgnoreCase(required("BITACORA_BO2_SQL_SHA256")),"Cambio la fuente SQL revisada");
            DataSource ds=new GuardedSource(required("BITACORA_BO2_PASSWORD"));
            try(Connection c=ds.getConnection()) {history(c,10);require(tables(c).isEmpty(),"Existen tablas BO2; posible aplicacion parcial. No continuar");controls(c);}
            Flyway flyway=Flyway.configure().dataSource(ds)
                .locations("filesystem:"+project.resolve("src/main/resources/db/migration").toString().replace('\\','/'),
                           "filesystem:"+project.resolve("src/main/resources/db/supervisor").toString().replace('\\','/'),
                           "filesystem:"+stage.toString().replace('\\','/'))
                .failOnMissingLocations(true).target("11").cleanDisabled(true)
                .baselineOnMigrate(false).outOfOrder(false).validateOnMigrate(true)
                .ignoreMigrationPatterns("*:pending").load();
            flyway.validate();
            MigrationInfo[] pending=flyway.info().pending();
            require(pending.length==1 && pending[0].getVersion()!=null
                && "11".equals(pending[0].getVersion().toString()) && SCRIPT.equals(pending[0].getScript()),
                "Solo V11 R2 puede estar pendiente; no se aplicaran otras migraciones");
            try(Connection c=ds.getConnection()){history(c,10);controls(c);require(tables(c).isEmpty(),"Tablas aparecieron concurrentemente");}
            migrationStarted=true;
            flyway.migrate();
            flyway.validate();
            require(flyway.info().pending().length==0,"Quedaron migraciones pendientes inesperadas");
            try(Connection c=ds.getConnection()) {
                history(c,11);controls(c);
                require(tables(c).equals(Set.of("bitacora_operativa_eventos","bitacora_operativa_avisos")),"Faltan tablas V11");
                Set<String> checks=new HashSet<>();
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('bitacora_operativa_eventos','bitacora_operativa_avisos') AND CONSTRAINT_TYPE='CHECK'")) {
                    while(r.next())checks.add(r.getString(1));
                }
                require(checks.equals(CHECKS),"No coincide la presencia de los 14 CHECK de V11");
                for(String t:List.of("bitacora_operativa_eventos","bitacora_operativa_avisos")) {
                    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();require(r.getLong(1)==0,"Las tablas nuevas deben quedar vacias");}
                    try(PreparedStatement s=c.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                        s.setString(1,t);try(ResultSet r=s.executeQuery()){require(r.next() && "InnoDB".equalsIgnoreCase(r.getString(1)),"Motor inesperado");}
                    }
                }
            }
            System.out.println("V11 aplicada y validada por Flyway; dos tablas vacias y 14 CHECK presentes. No demuestra enforcement ni funciones de avisos/correo.");
        } catch(Exception ex) {
            // No imprimir credenciales, entorno, mensajes JDBC ni causas encadenadas.
            if(migrationStarted) throw new IllegalStateException("BO2 no confirmado despues de iniciar migracion. DDL puede ser parcial. Conservar carpeta local; no reintentar ni reparar Flyway automaticamente.");
            throw new IllegalStateException("Prevalidacion BO2 no superada. No se invoco migrate. Revisar identidad, respaldo, conteos, fuentes e historial; no cambiar permisos automaticamente.");
        }
    }
    private static final class GuardedSource implements DataSource {
        private final String password;
        GuardedSource(String password){this.password=password;}
        public Connection getConnection() throws SQLException {
            Connection c=DriverManager.getConnection(URL,"bitacora_app",password);
            try{identity(c);return c;}catch(RuntimeException|SQLException ex){c.close();throw new SQLException("Identidad BO2 no verificada");}
        }
        public Connection getConnection(String u,String p) throws SQLException {throw new SQLException("Credenciales alternativas no permitidas");}
        public PrintWriter getLogWriter(){return null;}
        public void setLogWriter(PrintWriter w) throws SQLException {if(w!=null)throw new SQLException("Registro JDBC no permitido");}
        public void setLoginTimeout(int seconds){}
        public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getLogger("bitacora.bo2");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("unwrap no admitido");}
        public boolean isWrapperFor(Class<?> type){return false;}
        public String toString(){return "BO2 DataSource [credenciales ocultas]";}
    }
}
