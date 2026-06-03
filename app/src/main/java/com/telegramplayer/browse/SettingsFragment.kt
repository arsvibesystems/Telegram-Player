package com.telegramplayer.browse

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.telegramplayer.R
import com.telegramplayer.data.PlayerManager

class SettingsFragment : GuidedStepSupportFragment() {

    private val playerManager: PlayerManager by lazy { PlayerManager(requireContext()) }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Video Player",
            "Select the default video player for streaming",
            "Settings",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val players = playerManager.getAvailablePlayers()
        val selectedPackage = playerManager.getSelectedPlayer()

        val isAskSelected = selectedPackage == PlayerManager.PACKAGE_ASK_EVERY_TIME
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(-1L)
                .title("Ask every time")
                .description(if (isAskSelected) "Currently selected" else "Select player when opening a file")
                .checkSetId(1)
                .checked(isAskSelected)
                .build()
        )

        if (players.isEmpty()) {
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(-2L)
                    .title("No video players found")
                    .description("Please install a video player like VLC or MX Player")
                    .infoOnly(true)
                    .build()
            )
            return
        }

        players.forEachIndexed { index, player ->
            val isSelected = player.packageName == selectedPackage
            
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(index.toLong())
                    .title(player.label)
                    .description(if (isSelected) "Currently selected" else null)
                    .icon(player.icon)
                    .checkSetId(1)
                    .checked(isSelected)
                    .build()
            )
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == -1L) {
            playerManager.saveSelectedPlayer(PlayerManager.PACKAGE_ASK_EVERY_TIME)
            requireActivity().finish()
            return
        } else if (action.id == -2L) {
            return
        }

        val players = playerManager.getAvailablePlayers()
        val index = action.id.toInt()
        
        if (index in players.indices) {
            val selectedPlayer = players[index]
            playerManager.saveSelectedPlayer(selectedPlayer.packageName)
            requireActivity().finish()
        }
    }
}
