/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is a free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package net.pms.newgui;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.RowSpec;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.metal.MetalIconFactory;
import net.pms.Messages;
import net.pms.configuration.RendererConfiguration;
import net.pms.configuration.RendererConfigurations;
import net.pms.newgui.components.CustomJButton;
import net.pms.renderers.Renderer;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RendererPanel extends JPanel {

	private static final long serialVersionUID = 5130146620433713605L;

	private static final Logger LOGGER = LoggerFactory.getLogger(RendererPanel.class);
	private static final RowSpec RSPEC = RowSpec.decode("center:pref");

	private final Renderer renderer;
	private final CellConstraints cc = new CellConstraints();
	private JPanel editBar;
	private boolean ready = false;

	public RendererPanel(final Renderer renderer) {
		this.renderer = renderer;
	}

	public JPanel buildPanel() {
		FormLayout layout = new FormLayout("left:pref, 400:grow");
		PanelBuilder builder = new PanelBuilder(layout);
		builder.border(new EmptyBorder(10, 10, 10, 10));
		int y = 0;

		builder.appendRow(RSPEC);
		editBar = new JPanel();
		editBar.setLayout(new BoxLayout(editBar, BoxLayout.X_AXIS));
		builder.add(editBar, cc.xyw(1, ++y, 2));
		if (/*renderer.loaded &&*/ !renderer.isFileless()) {
			buildEditBar(false);
		}
		builder.appendRow(RSPEC);
		builder.addLabel(" ", cc.xy(1, ++y));

		y = addMap(renderer.getDetails(), builder, y);
		if (renderer.isUpnp()) {
			y = addStrings("Services", WordUtils.wrap(StringUtils.join(renderer.getUpnpServices(), ", "), 60).split("\n"),
				builder, y);
		}

		if (renderer.isControllable()) {
			builder.appendRow(RSPEC);
			builder.addLabel(" ", cc.xy(1, ++y));
			builder.appendRow(RSPEC);
			builder.addSeparator(Messages.getString("Controls"), cc.xyw(1, ++y, 2));
			builder.appendRow(RSPEC);
			builder.add(new PlayerControlPanel(renderer.getPlayer()), cc.xyw(1, ++y, 2));
		}
		return builder.getPanel();
	}

	public void buildEditBar(boolean updateUI) {
		boolean customized = renderer.isCustomized();
		boolean repack = ready && editBar.getComponentCount() == 0;
		editBar.removeAll();
		editBar.add(customized ? referenceButton() : editButton(true));
		if (renderer.getFile() != null) {
			editBar.add(Box.createHorizontalGlue());
			editBar.add(customized ? editButton(false) : customizeButton());
		}
		if (repack) {
			SwingUtilities.getWindowAncestor(this).pack();
		} else if (updateUI) {
			editBar.updateUI();
		}
	}

	public JButton customizeButton() {
		final CustomJButton open = new CustomJButton("+", MetalIconFactory.getTreeLeafIcon());
		open.setHorizontalTextPosition(SwingConstants.CENTER);
		open.setForeground(Color.lightGray);
		open.setToolTipText(Messages.getString("CustomizeThisDevice"));
		open.setFocusPainted(false);
		open.addActionListener((final ActionEvent e) -> {
			File f = chooseConf(RendererConfigurations.getWritableRenderersDir(), renderer.getDefaultFilename());
			if (f != null) {
				File file = RendererConfigurations.createDeviceFile(renderer, f.getName(), true);
				buildEditBar(true);
				try {
					Desktop.getDesktop().open(file);
				} catch (IOException ioe) {
					LOGGER.debug("Failed to open default desktop application: " + ioe);
				}
			}
		});
		return open;
	}

	public JButton referenceButton() {
		final File ref = renderer.getParentFile();
		final CustomJButton open = new CustomJButton(MetalIconFactory.getTreeLeafIcon());
		boolean exists = ref != null && ref.exists();
		open.setToolTipText(exists ? (Messages.getString("OpenParentConfiguration") + ": " + ref) : Messages.getString("NoParentConfiguration"));
		open.setFocusPainted(false);
		open.addActionListener((final ActionEvent e) -> {
			try {
				Desktop.getDesktop().open(ref);
			} catch (IOException ioe) {
				LOGGER.debug("Failed to open default desktop application: " + ioe);
			}
		});
		if (!exists) {
			open.setText("!");
			open.setHorizontalTextPosition(SwingConstants.CENTER);
			open.setForeground(Color.lightGray);
			open.setEnabled(false);
		}
		return open;
	}

	public JButton editButton(final boolean create) {
		final File file = create ? renderer.getUsableFile() : renderer.getFile();
		final String buttonText;
		if (file.exists() || !create) {
			buttonText = "<html>" + file.getName() + "</html>";
		} else {
			buttonText = "<html><font color=blue>" + Messages.getString("StartNewConfigurationFile") + ":</font> " + file.getName() + "</html>";
		}
		final CustomJButton open = new CustomJButton(buttonText, MetalIconFactory.getTreeLeafIcon());
		open.setToolTipText(file.getAbsolutePath());
		open.setFocusPainted(false);
		open.addActionListener((final ActionEvent e) -> {
			boolean exists = file.isFile() && file.exists();
			File f = file;
			if (!exists && create) {
				f =  chooseConf(RendererConfigurations.getWritableRenderersDir(), file.getName());
				if (f != null) {
					File ref = chooseReferenceConf();
					if (ref != null) {
						RendererConfigurations.createRendererFile(renderer, f, true, ref);
						open.setText(f.getName());
						exists = true;
					}
				}
			}
			if (exists) {
				try {
					Desktop.getDesktop().open(f);
				} catch (IOException ioe) {
					LOGGER.debug("Failed to open default desktop application: " + ioe);
				}
			} else {
				// Conf no longer exists, repair the edit bar
				buildEditBar(true);
			}
		});
		return open;
	}

	public File chooseConf(final File dir, final String filename) {
		final File file = new File(filename);
		JFileChooser fc = new JFileChooser(dir) {
			private static final long serialVersionUID = -3606991702534289691L;

			@Override
			public boolean isTraversable(File d) {
				return dir.equals(d); // Disable navigation
			}

			@Override
			public void approveSelection() {
				if (getSelectedFile().exists()) {
					int result = JOptionPane.showConfirmDialog(
						this,
						Messages.getString("OverwriteExistingFile"),
						Messages.getString("FileExists"),
						JOptionPane.YES_NO_CANCEL_OPTION
					);
					if (result == JOptionPane.CANCEL_OPTION || result == JOptionPane.NO_OPTION) {
						setSelectedFile(file);
					} else if (result == JOptionPane.CLOSED_OPTION) {
						return;
					}
				}
				super.approveSelection();
			}
		};
		fc.setAcceptAllFileFilterUsed(false);
		FileNameExtensionFilter filter = new FileNameExtensionFilter("Conf Files", "conf");
		fc.addChoosableFileFilter(filter);
		fc.setAcceptAllFileFilterUsed(false);
		// Current dir must be set explicitly before setting selected file (despite constructor call above)
		fc.setCurrentDirectory(dir);
		fc.setSelectedFile(file);
		fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fc.setDialogTitle(Messages.getString("SpecifyFileName"));
		if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			return fc.getSelectedFile();
		}
		return null;
	}

	public File chooseReferenceConf() {
		JFileChooser fc = new JFileChooser(RendererConfigurations.getRenderersDir());
		fc.setAcceptAllFileFilterUsed(false);
		FileNameExtensionFilter filter = new FileNameExtensionFilter("Conf Files", "conf");
		fc.addChoosableFileFilter(filter);
		fc.setAcceptAllFileFilterUsed(true);
		File defaultRef = new File(RendererConfigurations.getRenderersDir(), "DefaultRenderer.conf");
		if (defaultRef.exists()) {
			fc.setSelectedFile(defaultRef);
		}
		fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		return switch (fc.showDialog(this, Messages.getString("SelectReferenceFile"))) {
			case JFileChooser.APPROVE_OPTION -> fc.getSelectedFile();
			case JFileChooser.CANCEL_OPTION -> RendererConfiguration.NOFILE;
			default -> null;
		};
	}

	public int addItem(String key, String value, PanelBuilder builder, int y) {
		builder.appendRow(RSPEC);
		builder.addLabel(key.length() > 0 ? key + ":  " : "", cc.xy(1, ++y));
		JTextField val = new JTextField(value);
		val.setEditable(false);
		val.setBackground(Color.white);
		builder.add(val, cc.xy(2, y));
		return y;
	}

	public int addMap(Map<String, String> map, PanelBuilder builder, int y) {
		for (Map.Entry<String, String> entry : map.entrySet()) {
			y = addItem(entry.getKey(), entry.getValue(), builder, y);
		}
		return y;
	}

	public int addStrings(String title, String[] strings, PanelBuilder builder, int y) {
		for (String string : strings) {
			y = addItem(title, string, builder, y);
			title = "";
		}
		return y;
	}

	public int addList(String title, List<String> list, PanelBuilder builder, int y) {
		for (String item : list) {
			y = addItem(title, item, builder, y);
			title = "";
		}
		return y;
	}

	public void update() {
		removeAll();
		add(buildPanel());
		JFrame top = (JFrame) SwingUtilities.getWindowAncestor(this);
		if (top != null) {
			top.setTitle(renderer.getRendererName() + (renderer.isOffline() ? "  [offline]" : ""));
			top.pack();
		}
		ready = true;
	}

}
