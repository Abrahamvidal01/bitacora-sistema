package com.sistema.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.swing.*;
import java.util.Arrays;

public class GeneradorBCrypt {

    public static void main(String[] args) {
        JPasswordField campo = new JPasswordField(25);

        int opcion = JOptionPane.showConfirmDialog(
                null,
                campo,
                "Contraseña para generar BCrypt",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        char[] caracteres = campo.getPassword();

        try {
            if (opcion != JOptionPane.OK_OPTION) {
                return;
            }

            if (caracteres.length == 0) {
                System.out.println("No ingresaste una contraseña.");
                return;
            }

            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String hash = encoder.encode(new String(caracteres));

            System.out.println("Hash BCrypt: " + hash);
        } finally {
            Arrays.fill(caracteres, '\0');
            campo.setText("");
        }
    }
}
