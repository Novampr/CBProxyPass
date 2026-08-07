package org.cloudburstmc.proxypass.ui.components;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.cloudburstmc.proxypass.Configuration;
import org.cloudburstmc.proxypass.auth.Account;
import org.cloudburstmc.proxypass.auth.AuthHandler;
import org.cloudburstmc.proxypass.ui.components.swing.JAddressField;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ConnectionHandler extends JPanel {
    private final JButton startStop = new JButton("Start");
    private final JComboBox<UIAccount> accountSelector;

    public ConnectionHandler(Configuration configuration, ButtonHandler buttonHandler) {
        this.setLayout(new BorderLayout(2, 2));

        JPanel accountsPanel = new JPanel();
        accountsPanel.setLayout(new BorderLayout());

        accountSelector = new JComboBox<>();
        accountSelector.addItem(new UIAccount(null));
        JsonArray accounts = AuthHandler.readRawAccounts();

        for (JsonElement account : accounts) {
            accountSelector.addItem(new UIAccount(account.getAsJsonObject()));
        }
        accountsPanel.add(accountSelector, BorderLayout.CENTER);

        JButton addAccountButton = new JButton("+");
        addAccountButton.addActionListener(e -> {
            Thread t = new Thread(() -> {
                BedrockAuthManager authManager;
                AtomicReference<JDialog> dialog = new AtomicReference<>();
                try {
                    authManager = AuthHandler.AUTH_MANAGER.login(DeviceCodeMsaAuthService::new, (Consumer<MsaDeviceCode>) msaDeviceCode -> {
                        URI verificationUri = URI.create(
                                msaDeviceCode.getVerificationUri()
                                        + (URI.create(msaDeviceCode.getVerificationUri()).getQuery() != null ? '&' : '?') + "otc=" + msaDeviceCode.getUserCode()
                        );

                        QRCodeWriter writer = new QRCodeWriter();

                        BitMatrix matrix;
                        try {
                            matrix = writer.encode(verificationUri.toString(), BarcodeFormat.QR_CODE, 0, 0); // Will force the smallest size possible
                        } catch (WriterException ex) {
                            throw new RuntimeException(ex);
                        }

                        SwingUtilities.invokeLater(() -> {
                            dialog.set(new JDialog());
                            dialog.get().setTitle("Sign-in");
                            dialog.get().setLayout(new BorderLayout(0, 10));

                            dialog.get().add(new JLabel("Scan the QR code below or click the button to sign in."), BorderLayout.NORTH);

                            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);

                            for (int x = 0; x < image.getWidth(); x++) {
                                for (int y = 0; y < image.getHeight(); y++) {
                                    if (matrix.get(x, y)) {
                                        image.setRGB(x, y, Color.WHITE.getRGB());
                                    }
                                    image.setRGB(x, y, matrix.get(x, y) ? 0 : Color.WHITE.getRGB());
                                }
                            }
                            dialog.get().add(new JLabel(new ImageIcon(image.getScaledInstance(matrix.getWidth() * 5, matrix.getHeight() * 5, BufferedImage.SCALE_DEFAULT))), BorderLayout.CENTER);

                            JButton button = new JButton("Sign-in");
                            button.addActionListener(ev -> {
                                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                    try {
                                        Desktop.getDesktop().browse(verificationUri);
                                    } catch (IOException ex) {
                                        throw new RuntimeException(ex);
                                    }
                                } else {
                                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                                            new StringSelection(verificationUri.toString()), null
                                    );

                                    JOptionPane.showMessageDialog(dialog.get(), "Copied link to clipboard! (Java reports your system as unable to open links.)", "Copied!", JOptionPane.INFORMATION_MESSAGE);
                                }
                            });
                            dialog.get().add(button, BorderLayout.SOUTH);

                            dialog.get().pack();
                            dialog.get().setLocationRelativeTo(null);
                            dialog.get().setVisible(true);
                        });
                    });
                } catch (IOException | InterruptedException ex) {
                    throw new RuntimeException(ex);
                } catch (TimeoutException ex) {
                    JDialog d = dialog.get();
                    JOptionPane.showMessageDialog(d, "The login has timed out, please try again.", "Error!", JOptionPane.INFORMATION_MESSAGE);
                    if (d != null) d.dispose();
                    return;
                }
                Account account = new Account(authManager);
                try {
                    account.refresh();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
                JsonObject accountObject = account.toJson();
                AuthHandler.addAccount(accountObject);
                accountSelector.addItem(new UIAccount(accountObject));
                accountSelector.setSelectedIndex(accountSelector.getItemCount() - 1);
            });
            t.start();
        });
        accountsPanel.add(addAccountButton, BorderLayout.EAST);

        this.add(accountsPanel, BorderLayout.WEST);

        JPanel addresses = new JPanel();
        addresses.setLayout(new GridLayout(1, 2, 2, 2));
        JAddressField proxy;
        addresses.add(proxy = new JAddressField("Proxy Address: ", configuration.getProxy().getHost(), configuration.getProxy().getPort()));
        JAddressField target;
        addresses.add(target = new JAddressField("Target Address: ", configuration.getDestination().getHost(), configuration.getDestination().getPort()));
        this.add(addresses, BorderLayout.CENTER);

        startStop.addActionListener(e -> {
            startStop.setEnabled(false);
            try {
                buttonHandler.handle(this, proxy.getAddress(), target.getAddress(), ((UIAccount) accountSelector.getSelectedItem()).getAccountObject());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
        this.add(startStop, BorderLayout.EAST);
    }

    public void onStart() {
        startStop.setText("Stop");
        startStop.setEnabled(true);
        accountSelector.setEnabled(false);
    }

    public void onStop() {
        startStop.setText("Start");
        startStop.setEnabled(true);
        accountSelector.setEnabled(true);
    }

    @FunctionalInterface
    public interface ButtonHandler {
        void handle(ConnectionHandler handler, InetSocketAddress proxyAddress, InetSocketAddress targetAddress, JsonObject accountObject) throws IOException;
    }

    @AllArgsConstructor
    private static class UIAccount {
        @Getter
        private final JsonObject accountObject;

        public String name() {
            return AuthHandler.getAccountName(accountObject);
        }

        @Override
        public String toString() {
            return name() == null ? "No Account" : name();
        }
    }
}