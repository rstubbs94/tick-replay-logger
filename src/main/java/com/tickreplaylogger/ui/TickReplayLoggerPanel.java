/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.ui;

import com.google.gson.*;
import com.tickreplaylogger.*;
import com.tickreplaylogger.capture.*;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.ui.playback.*;
import com.tickreplaylogger.util.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import net.runelite.client.game.*;
import net.runelite.client.ui.*;
import static com.tickreplaylogger.ui.LiveTickPanel.createTextLabel;

/**
 * Main panel for Tick Replay Logger plugin.
 * Shows list of recent deaths and saved recordings with expandable tick timelines,
 * or a live timeline of the current tick buffer.
 */
public class TickReplayLoggerPanel extends PluginPanel
{
	private static final String RECORDINGS_VIEW = "recordings";
	private static final String LIVE_VIEW = "live";
	private static final String PLAYBACK_VIEW = "playback";

	private static final String LIVE_SAVE_DEFAULT_TEXT = "Save Snapshot";
	private static final int LIVE_SAVE_FEEDBACK_DURATION_MS = 1500;

	private final RecordingStorage recordingStorage;
	private final SpriteManager spriteManager;
	private TickRecorder tickRecorder;

	// View switching
	private final CardLayout cardLayout;
	private final JPanel contentPanel;
	private final JButton toggleViewBtn;
	private String currentView = RECORDINGS_VIEW;
	private String previousView = RECORDINGS_VIEW; // For returning from playback

	// Recordings view components (deaths + saved)
	private final JPanel recordingsListContainer;
	private final JLabel noRecordingsLabel;

	// Live view components
	private final JPanel liveTimelineContainer;
	private final JLabel liveStatusLabel;
	private final JButton liveStartStopBtn;
	private final JButton liveSaveBtn;
	private boolean liveRecording = true; // Controls display updates
	private boolean liveViewEnabled = true;
	private TickRecording.CapturedWindow pausedCapture;
	private Consumer<Consumer<TickRecording.CapturedWindow>> liveCaptureRequester;
	private long pauseGeneration;
	private boolean liveSavePending;
	private volatile boolean disposed;
	private Timer liveSaveFeedbackTimer;
	private boolean liveSaveFeedbackActive;
	private static final DateTimeFormatter SNAPSHOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// Playback view components
	private final PlaybackPanel playbackPanel;

	// Gson for JSON export (injected from client)
	private final Gson gson;
	private SwingWorker<String, Void> clipboardExport;

	// Config, for display options like collapsing unchanged ticks
	private final TickReplayLoggerConfig config;

	public TickReplayLoggerPanel(RecordingStorage recordingStorage, SpriteManager spriteManager, Gson gson,
		TickReplayLoggerConfig config)
	{
		super(false);
		this.recordingStorage = recordingStorage;
		this.spriteManager = spriteManager;
		this.gson = gson;
		this.config = config;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header with title and view toggle
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel titleLabel = new JLabel("Tick Replay Logger");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
		headerPanel.add(titleLabel, BorderLayout.WEST);

		// Toggle view button (right)
		JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		headerButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		toggleViewBtn = new JButton("Live View");
		toggleViewBtn.setToolTipText("Switch to live timeline view");
		toggleViewBtn.addActionListener(e -> toggleView());
		headerButtons.add(toggleViewBtn);

		headerPanel.add(headerButtons, BorderLayout.EAST);

		add(headerPanel, BorderLayout.NORTH);

		// Content panel with CardLayout for view switching
		cardLayout = new CardLayout();
		contentPanel = new JPanel(cardLayout);
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// === Recordings View (Deaths + Saved) ===
		JPanel recordingsViewPanel = new JPanel(new BorderLayout());
		recordingsViewPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Recordings list container
		recordingsListContainer = new JPanel(new GridBagLayout())
		{
			@Override
			public Dimension getPreferredSize()
			{
				Dimension d = super.getPreferredSize();
				Container parent = getParent();
				if (parent instanceof JViewport)
				{
					d.width = parent.getWidth();
				}
				return d;
			}
		};
		recordingsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		noRecordingsLabel = new JLabel("No recordings yet");
		noRecordingsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noRecordingsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		noRecordingsLabel.setBorder(new EmptyBorder(20, 10, 20, 10));

		JScrollPane recordingsScrollPane = new JScrollPane(recordingsListContainer);
		recordingsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		recordingsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recordingsScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		recordingsScrollPane.setBorder(null);

		recordingsViewPanel.add(recordingsScrollPane, BorderLayout.CENTER);

		// === Live View ===
		JPanel liveViewPanel = new JPanel(new BorderLayout());
		liveViewPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Live header with status and buttons on separate rows
		JPanel liveHeader = new JPanel();
		liveHeader.setLayout(new BoxLayout(liveHeader, BoxLayout.Y_AXIS));
		liveHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		liveHeader.setBorder(new EmptyBorder(6, 10, 6, 10));

		// Row 1: Buttons
		JPanel liveButtonsPanel = new JPanel();
		liveButtonsPanel.setLayout(new BoxLayout(liveButtonsPanel, BoxLayout.X_AXIS));
		liveButtonsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		liveButtonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		liveButtonsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		liveButtonsPanel.add(Box.createHorizontalGlue());
		liveStartStopBtn = new JButton("Pause");
		liveStartStopBtn.setMargin(new Insets(2, 6, 2, 6));
		liveStartStopBtn.setToolTipText("Pause/resume live updates");
		liveStartStopBtn.addActionListener(e -> toggleLiveUpdates());
		liveButtonsPanel.add(liveStartStopBtn);
		liveButtonsPanel.add(Box.createHorizontalStrut(5));

		JButton liveExportBtn = new JButton("Export");
		liveExportBtn.setMargin(new Insets(2, 6, 2, 6));
		liveExportBtn.setToolTipText("Copy current tick buffer to clipboard");
		liveExportBtn.addActionListener(e -> exportLiveBuffer());
		liveButtonsPanel.add(liveExportBtn);
		liveButtonsPanel.add(Box.createHorizontalStrut(5));

		liveSaveBtn = new JButton(LIVE_SAVE_DEFAULT_TEXT);
		liveSaveBtn.setMargin(new Insets(2, 6, 2, 6));
		liveSaveBtn.setToolTipText("Save current tick buffer as a recording");
		liveSaveBtn.setBackground(new Color(50, 80, 50));
		liveSaveBtn.setForeground(new Color(150, 200, 150));
		liveSaveBtn.addActionListener(e -> saveLiveBuffer());
		liveButtonsPanel.add(liveSaveBtn);

		liveHeader.add(liveButtonsPanel);

		// Row 2: Status
		liveStatusLabel = new JLabel("Recording: Active");
		liveStatusLabel.setForeground(new Color(80, 200, 80));
		liveStatusLabel.setFont(liveStatusLabel.getFont().deriveFont(Font.BOLD, 11f));
		liveStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		liveHeader.add(liveStatusLabel);

		liveViewPanel.add(liveHeader, BorderLayout.NORTH);

		// Live timeline container
		liveTimelineContainer = new JPanel(new GridBagLayout())
		{
			@Override
			public Dimension getPreferredSize()
			{
				Dimension d = super.getPreferredSize();
				Container parent = getParent();
				if (parent instanceof JViewport)
				{
					d.width = parent.getWidth();
				}
				return d;
			}
		};
		liveTimelineContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane liveScrollPane = new JScrollPane(liveTimelineContainer);
		liveScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		liveScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		liveScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		liveScrollPane.setBorder(null);

		liveViewPanel.add(liveScrollPane, BorderLayout.CENTER);

		// === Playback View ===
		playbackPanel = new PlaybackPanel(this::returnFromPlayback, spriteManager);

		// Add views to card layout
		contentPanel.add(recordingsViewPanel, RECORDINGS_VIEW);
		contentPanel.add(liveViewPanel, LIVE_VIEW);
		contentPanel.add(playbackPanel, PLAYBACK_VIEW);

		add(contentPanel, BorderLayout.CENTER);

		updateLiveSaveButtonState();
		// Initial refresh
		refreshRecordingsList();
	}

	/**
	 * Set the tick recorder for live view updates.
	 */
	public void setTickRecorder(TickRecorder tickRecorder)
	{
		this.tickRecorder = tickRecorder;
		updateLiveSaveButtonState();
	}

	/**
	 * Toggle between recordings view and live view.
	 */
	private void toggleView()
	{
		if (!liveViewEnabled)
		{
			return;
		}

		if (currentView.equals(RECORDINGS_VIEW))
		{
			currentView = LIVE_VIEW;
			toggleViewBtn.setText("Recordings");
			toggleViewBtn.setToolTipText("Switch to recordings list view");
			cardLayout.show(contentPanel, LIVE_VIEW);
			refreshLiveTimeline();
		}
		else if (currentView.equals(LIVE_VIEW))
		{
			currentView = RECORDINGS_VIEW;
			toggleViewBtn.setText("Live View");
			toggleViewBtn.setToolTipText("Switch to live timeline view");
			cardLayout.show(contentPanel, RECORDINGS_VIEW);
		}
		// If in playback view, toggle goes to recordings view
		else if (currentView.equals(PLAYBACK_VIEW))
		{
			returnFromPlayback();
		}
	}

	/**
	 * Show the playback view for a recording.
	 */
	private void showPlayback(TickRecording recording)
	{
		previousView = currentView;
		currentView = PLAYBACK_VIEW;
		toggleViewBtn.setText("Recordings");
		toggleViewBtn.setToolTipText("Return to recordings list");
		playbackPanel.loadRecording(recording);
		cardLayout.show(contentPanel, PLAYBACK_VIEW);
	}

	/**
	 * Return from playback view to previous view.
	 */
	private void returnFromPlayback()
	{
		currentView = RECORDINGS_VIEW; // Always return to recordings view
		toggleViewBtn.setText("Live View");
		toggleViewBtn.setToolTipText("Switch to live timeline view");
		cardLayout.show(contentPanel, RECORDINGS_VIEW);
	}

	/**
	 * Toggle live updates on/off.
	 */
	private void toggleLiveUpdates()
	{
		long generation = ++pauseGeneration;
		liveRecording = !liveRecording;
		if (liveRecording)
		{
			liveStartStopBtn.setText("Pause");
			liveStatusLabel.setText("Recording: Active");
			liveStatusLabel.setForeground(new Color(80, 200, 80));
			pausedCapture = null;
			refreshLiveTimeline();
		}
		else
		{
			liveStartStopBtn.setText("Resume");
			liveStatusLabel.setText("Recording: Paused");
			liveStatusLabel.setForeground(new Color(255, 180, 80));
			pausedCapture = null;
			requestCapture(capture ->
			{
				if (disposed || liveRecording || generation != pauseGeneration)
				{
					return;
				}
				pausedCapture = capture;
				refreshLiveTimeline();
			});
		}

		updateLiveSaveButtonState();
	}

	/**
	 * Refresh the panel - called after death recording.
	 */
	public void refresh()
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			if (!disposed)
			{
				refreshRecordingsList();
			}
		}
		else
		{
			SwingUtilities.invokeLater(this::refresh);
		}
	}

	/** Called after asynchronous storage finishes, including its initial load. */
	public void storageCompleted(Throwable error)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (disposed)
			{
				return;
			}
			refresh();
			if (error != null)
			{
				JOptionPane.showMessageDialog(this,
					"Could not load or save recordings. Existing files have been kept. Check the RuneLite log for details.",
					"Recording storage", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	/** Stop UI work immediately; accepted storage operations finish independently. */
	public void dispose()
	{
		disposed = true;
		SwingUtilities.invokeLater(() ->
		{
			if (clipboardExport != null)
			{
				clipboardExport.cancel(false);
			}

			if (liveSaveFeedbackTimer != null)
			{
				liveSaveFeedbackTimer.stop();
			}
			playbackPanel.dispose();
			pausedCapture = null;
			liveCaptureRequester = null;
			pauseGeneration++;
			tickRecorder = null;
			recordingsListContainer.removeAll();
			liveTimelineContainer.removeAll();
		});
	}

	/**
	 * Called on each game tick to update live view.
	 */
	public void onGameTick()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (!disposed && liveViewEnabled && currentView.equals(LIVE_VIEW) && liveRecording)
			{
				refreshLiveTimeline();
			}
		});
	}

	/**
	 * Refresh the recordings list view (deaths + saved).
	 */
	private void refreshRecordingsList()
	{
		if (disposed)
		{
			return;
		}
		renderRecordingsList(recordingStorage.getDeaths(), recordingStorage.getSavedRecordings());
	}

	private void renderRecordingsList(List<TickRecording> deaths, List<TickRecording> saved)
	{
		if (disposed)
		{
			return;
		}
		recordingsListContainer.removeAll();

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.NORTH;
		gbc.insets = new Insets(3, 0, 3, 0);

		boolean hasContent = false;

		// === Recent Deaths Section (shown first - limited list) ===
		if (!deaths.isEmpty())
		{
			hasContent = true;
			JPanel deathsHeader = createSectionHeader("☠ Recent Deaths (" + deaths.size() + ")",
				new Color(180, 80, 80));
			recordingsListContainer.add(deathsHeader, gbc);
			gbc.gridy++;

			for (int i = 0; i < deaths.size(); i++)
			{
				TickRecording death = deaths.get(i);
				RecordingEntryPanel entryPanel = new RecordingEntryPanel(death, i + 1, this::exportRecording,
					this::deleteDeath, this::showPlayback, this::saveRecording, // Save callback to move to saved
					this::renameRecording, spriteManager, false // isSaved = false
				);
				entryPanel.setCollapseUnchanged(config.collapseUnchangedTicks());
				recordingsListContainer.add(entryPanel, gbc);
				gbc.gridy++;
			}

			// Add spacing between sections
			if (!saved.isEmpty())
			{
				recordingsListContainer.add(Box.createVerticalStrut(10), gbc);
				gbc.gridy++;
			}
		}

		// === Saved Recordings Section ===
		if (!saved.isEmpty())
		{
			hasContent = true;
			JPanel savedHeader = createSectionHeader("Saved Recordings (" + saved.size() + ")", new Color(80, 150, 80));
			recordingsListContainer.add(savedHeader, gbc);
			gbc.gridy++;

			for (int i = 0; i < saved.size(); i++)
			{
				TickRecording recording = saved.get(i);
				RecordingEntryPanel entryPanel = new RecordingEntryPanel(recording, i + 1, this::exportRecording,
					this::deleteSavedRecording, this::showPlayback, null, // No save callback for already-saved items
					this::renameRecording, spriteManager, true // isSaved = true
				);
				entryPanel.setCollapseUnchanged(config.collapseUnchangedTicks());
				recordingsListContainer.add(entryPanel, gbc);
				gbc.gridy++;
			}
		}

		// Empty state
		if (!hasContent)
		{
			gbc.weighty = 1.0;
			recordingsListContainer.add(noRecordingsLabel, gbc);
		}
		else
		{
			gbc.weighty = 1.0;
			recordingsListContainer.add(Box.createVerticalGlue(), gbc);
		}

		recordingsListContainer.revalidate();
		recordingsListContainer.repaint();
	}

	/**
	 * Create a section header label.
	 */
	private JPanel createSectionHeader(String text, Color color)
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(new EmptyBorder(6, 10, 6, 10));

		JLabel label = createTextLabel(text, color, Font.BOLD, 13);
		header.add(label, BorderLayout.WEST);

		return header;
	}

	/**
	 * Refresh the live timeline view.
	 */
	private void refreshLiveTimeline()
	{
		if (tickRecorder == null)
		{
			return;
		}

		liveTimelineContainer.removeAll();

		List<TickData> chronological = liveRecording ? tickRecorder.getTickHistory()
			: pausedCapture == null ? Collections.emptyList() : pausedCapture.getTicks();

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.NORTH;
		gbc.insets = new Insets(1, 0, 1, 0);

		if (chronological.isEmpty())
		{
			JLabel emptyLabel = new JLabel("No ticks recorded yet");
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setBorder(new EmptyBorder(20, 10, 20, 10));
			gbc.weighty = 1.0;
			liveTimelineContainer.add(emptyLabel, gbc);
		}
		else
		{
			// Update status with tick count
			int tickCount = chronological.size();
			int currentTick = chronological.get(tickCount - 1).getTickNumber();
			String statusText = liveRecording ? "Recording: Active" : "Recording: Paused";
			statusText += " (" + tickCount + " ticks, #" + currentTick + ")";
			liveStatusLabel.setText(statusText);

			// Build display rows (optionally collapsing unchanged ticks), then render newest-first
			List<TickDisplay.Row> rows = TickDisplay.build(chronological, config.collapseUnchangedTicks());

			int rowIndex = 0;
			for (int r = rows.size() - 1; r >= 0; r--)
			{
				TickDisplay.Row row = rows.get(r);
				JPanel rowPanel = row.isGap() ? LiveTickPanel.createGapPanel(row.gapCount)
					: LiveTickPanel.createTickPanel(row.tick, rowIndex, spriteManager);
				liveTimelineContainer.add(rowPanel, gbc);
				gbc.gridy++;
				rowIndex++;
			}

			gbc.weighty = 1.0;
			liveTimelineContainer.add(Box.createVerticalGlue(), gbc);
		}

		liveTimelineContainer.revalidate();
		liveTimelineContainer.repaint();
		updateLiveSaveButtonState();
	}

	private void updateLiveSaveButtonState()
	{
		if (liveSaveBtn == null)
		{
			return;
		}

		if (liveSaveFeedbackActive)
		{
			// While feedback is active, keep current text/enabled state.
			return;
		}

		boolean hasPausedSnapshot = !liveRecording && pausedCapture != null && !pausedCapture.getTicks().isEmpty();
		boolean hasLiveTicks = liveRecording && tickRecorder != null && tickRecorder.getTickCount() > 0;
		liveSaveBtn.setEnabled(!liveSavePending && (hasPausedSnapshot || hasLiveTicks));
		liveSaveBtn.setText(LIVE_SAVE_DEFAULT_TEXT);
	}

	/**
	 * Copy a recording to the clipboard. Emits the full rich versioned export (scenes + per-tick world
	 * snapshots + events + summary) when it was captured for this death; falls back to the plain journal
	 * JSON for older recordings that predate rich capture. Local only — nothing is sent anywhere.
	 */
	private void exportRecording(TickRecording recording)
	{
		if (disposed || clipboardExport != null)
		{
			return;
		}
		boolean rich = recording.hasRichExport();
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		clipboardExport = new SwingWorker<String, Void>()
		{
			@Override
			protected String doInBackground()
			{
				return RecordingExport.forClipboard(recording, gson);
			}

			@Override
			protected void done()
			{
				if (disposed || isCancelled())
				{
					return;
				}
				try
				{
					String json = get();
					copyToClipboard(json);
					JOptionPane.showMessageDialog(
						TickReplayLoggerPanel.this, (rich ? "Full scene recording" : "Journal (no scene data)")
							+ " copied to clipboard (" + json.length() + " chars).",
						"Copied", JOptionPane.INFORMATION_MESSAGE);
				}
				catch (Exception failed)
				{
					JOptionPane.showMessageDialog(TickReplayLoggerPanel.this,
						"Could not copy the recording. Try again.", "Export failed", JOptionPane.ERROR_MESSAGE);
				}
				finally
				{
					clipboardExport = null;
					setCursor(Cursor.getDefaultCursor());
				}
			}
		};
		clipboardExport.execute();
	}

	/**
	 * Delete a death record.
	 */
	private void deleteDeath(TickRecording death)
	{
		int result = JOptionPane.showConfirmDialog(this, "Delete this death record?", "Confirm Delete",
			JOptionPane.YES_NO_OPTION);
		if (result == JOptionPane.YES_OPTION)
		{
			recordingStorage.deleteDeath(death).whenComplete((ignored, error) -> storageCompleted(error));
		}
	}

	/**
	 * Save a recording to the saved recordings list.
	 */
	private void saveRecording(TickRecording recording)
	{
		recordingStorage.saveRecording(recording).whenComplete((ignored, error) -> storageCompleted(error));
	}

	/**
	 * Rename a recording (applies to both saved and recent entries).
	 */
	private void renameRecording(TickRecording recording)
	{
		if (recording == null)
		{
			return;
		}

		String currentName = recording.getKillerName();
		String newName = (String) JOptionPane.showInputDialog(this, "Enter a new name:", "Rename Recording",
			JOptionPane.PLAIN_MESSAGE, null, null, currentName);

		if (newName == null)
		{
			return;
		}

		String trimmed = newName.trim();
		if (trimmed.isEmpty() || trimmed.equals(currentName))
		{
			return;
		}

		recordingStorage.renameRecording(recording, trimmed).whenComplete((ignored, error) -> storageCompleted(error));
	}

	/**
	 * Delete a saved recording.
	 */
	private void deleteSavedRecording(TickRecording recording)
	{
		int result = JOptionPane.showConfirmDialog(this, "Delete this saved recording?", "Confirm Delete",
			JOptionPane.YES_NO_OPTION);
		if (result == JOptionPane.YES_OPTION)
		{
			recordingStorage.deleteSavedRecording(recording).whenComplete((ignored, error) -> storageCompleted(error));
		}
	}

	/**
	 * Export current live buffer to clipboard.
	 */
	private void exportLiveBuffer()
	{
		if (!liveRecording)
		{
			if (pausedCapture != null)
			{
				copyLiveCapture(pausedCapture);
			}
		}
		else
		{
			requestCapture(this::copyLiveCapture);
		}
	}

	private void copyLiveCapture(TickRecording.CapturedWindow capture)
	{
		if (disposed || capture == null || capture.getTicks().isEmpty())
		{
			return;
		}
		copyToClipboard(gson.toJson(capture.getTicks()));
		JOptionPane.showMessageDialog(this,
			"Live buffer (" + capture.getTicks().size() + " ticks) exported to clipboard!", "Export Successful",
			JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Save current live buffer as a recording.
	 */
	private void saveLiveBuffer()
	{
		if (liveSavePending)
		{
			return;
		}
		if (!liveRecording)
		{
			if (pausedCapture != null && !pausedCapture.getTicks().isEmpty())
			{
				saveCapture(pausedCapture);
			}
			return;
		}
		liveSavePending = true;
		updateLiveSaveButtonState();
		requestCapture(capture ->
		{
			if (capture == null || capture.getTicks().isEmpty())
			{
				liveSavePending = false;
				if (!disposed)
				{
					updateLiveSaveButtonState();
				}
				return;
			}
			saveCapture(capture);
		});
	}

	private void saveCapture(TickRecording.CapturedWindow capture)
	{
		String name = "Live Snapshot - " + LocalDateTime.now().format(SNAPSHOT_FORMAT);
		long timestamp = System.currentTimeMillis();
		liveSavePending = true;
		updateLiveSaveButtonState();
		recordingStorage.addSavedRecording(() ->
		{
			TickRecording prepared = TickRecording.fromPrepared(capture.getCompletion().get(), timestamp,
				"Live Snapshot", false);
			prepared.setKillerName(name);
			return prepared;
		}).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() ->
		{
			liveSavePending = false;
			if (disposed)
			{
				return;
			}
			storageCompleted(error);
			if (error == null)
			{
				showLiveSaveFeedback();
			}
			else
			{
				updateLiveSaveButtonState();
			}
		}));
	}

	public void setLiveCaptureRequester(Consumer<Consumer<TickRecording.CapturedWindow>> requester)
	{
		liveCaptureRequester = requester;
	}

	private void requestCapture(Consumer<TickRecording.CapturedWindow> ready)
	{
		if (liveCaptureRequester == null)
		{
			ready.accept(null);
			return;
		}
		liveCaptureRequester.accept(capture ->
		{
			if (SwingUtilities.isEventDispatchThread())
			{
				ready.accept(capture);
			}
			else
			{
				SwingUtilities.invokeLater(() -> ready.accept(capture));
			}
		});
	}

	/**
	 * Copy text to system clipboard.
	 */
	private void copyToClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private void showLiveSaveFeedback()
	{
		if (liveSaveBtn == null)
		{
			return;
		}

		if (liveSaveFeedbackTimer != null && liveSaveFeedbackTimer.isRunning())
		{
			liveSaveFeedbackTimer.stop();
		}

		liveSaveFeedbackActive = true;
		liveSaveBtn.setText("Saved!");
		liveSaveBtn.setEnabled(false);

		liveSaveFeedbackTimer = new Timer(LIVE_SAVE_FEEDBACK_DURATION_MS, e ->
		{
			liveSaveFeedbackActive = false;
			liveSaveBtn.setText(LIVE_SAVE_DEFAULT_TEXT);
			updateLiveSaveButtonState();
		});
		liveSaveFeedbackTimer.setRepeats(false);
		liveSaveFeedbackTimer.start();
	}

	/**
	 * Enable or disable live view (configuration controlled).
	 */
	public void setLiveViewEnabled(boolean enabled)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> setLiveViewEnabled(enabled));
			return;
		}
		if (disposed)
		{
			return;
		}
		liveViewEnabled = enabled;
		toggleViewBtn.setEnabled(enabled);
		if (enabled)
		{
			toggleViewBtn.setText(currentView.equals(LIVE_VIEW) ? "Recordings" : "Live View");
			toggleViewBtn.setToolTipText("Switch to live timeline view");
		}
		else
		{
			toggleViewBtn.setText("Live View (disabled)");
			toggleViewBtn.setToolTipText("Enable Live View in the config to use this feature");
			if (currentView.equals(LIVE_VIEW) || currentView.equals(PLAYBACK_VIEW))
			{
				currentView = RECORDINGS_VIEW;
				cardLayout.show(contentPanel, RECORDINGS_VIEW);
			}
		}
	}
}
