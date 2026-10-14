/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.messaging.ui.contact

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.ConnectionStateProvider
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: ContactsViewModel
    private val nodeRepository = FakeNodeRepository()
    private val packetRepository: PacketRepository = mock(MockMode.autofill)
    private val radioConfigRepository: RadioConfigRepository = mock(MockMode.autofill)
    private val connectionStateProvider: ConnectionStateProvider = mock(MockMode.autofill)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { connectionStateProvider.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        every { packetRepository.getUnreadCountTotal() } returns MutableStateFlow(0)
        every { radioConfigRepository.channelSetFlow } returns MutableStateFlow(ChannelSet())

        viewModel =
            ContactsViewModel(
                savedStateHandle = SavedStateHandle(),
                nodeRepository = nodeRepository,
                packetRepository = packetRepository,
                snackbarManager = SnackbarManager(),
                radioConfigRepository = radioConfigRepository,
                connectionStateProvider = connectionStateProvider,
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialization() {
        assertNotNull(viewModel)
    }

    @Test
    fun `unreadCountTotal reflects updates from repository`() = runTest(testDispatcher) {
        val countFlow = MutableStateFlow(0)
        every { packetRepository.getUnreadCountTotal() } returns countFlow

        // Re-init VM
        viewModel =
            ContactsViewModel(
                SavedStateHandle(),
                nodeRepository,
                packetRepository,
                SnackbarManager(),
                radioConfigRepository,
                connectionStateProvider,
            )

        viewModel.unreadCountTotal.test {
            assertEquals(0, awaitItem())
            countFlow.value = 5
            assertEquals(5, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleSectionCollapse flips section membership`() = runTest(testDispatcher) {
        viewModel.collapsedSections.test {
            assertEquals(emptySet(), awaitItem())

            viewModel.toggleSectionCollapse(ContactSection.CHANNELS.key)
            assertTrue(ContactSection.CHANNELS.key in awaitItem())

            viewModel.toggleSectionCollapse(ContactSection.CHANNELS.key)
            assertFalse(ContactSection.CHANNELS.key in awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `collapsedSections restores persisted state from SavedStateHandle`() = runTest(testDispatcher) {
        viewModel =
            ContactsViewModel(
                SavedStateHandle(mapOf("collapsed_contact_sections" to ContactSection.DIRECT_MESSAGES.key)),
                nodeRepository,
                packetRepository,
                SnackbarManager(),
                radioConfigRepository,
                connectionStateProvider,
            )

        viewModel.collapsedSections.test {
            assertEquals(setOf(ContactSection.DIRECT_MESSAGES.key), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contactList formats local voice note preview cleanly stripping file path`() = runTest(testDispatcher) {
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 12345))
        nodeRepository.setMyId(NodeAddress.ID_LOCAL)
        nodeRepository.setNodes(
            listOf(
                Node(num = 12345, user = User(id = NodeAddress.ID_LOCAL, short_name = "ME")),
                Node(num = 67890, user = User(id = "8!remote", short_name = "Alice", long_name = "Alice")),
            ),
        )

        val voicePacket =
            DataPacket(
                to = "8!remote",
                channel = 0,
                text =
                "[Voice Note: 0:01 | /data/user/0/com.geeksville.mesh.fdroid.debug/cache/audio_1727800000.wt]",
            )
                .apply {
                    from = NodeAddress.ID_LOCAL
                    time = 1000L
                }

        every { packetRepository.getContacts() } returns MutableStateFlow(mapOf("8!remote" to voicePacket))
        every { packetRepository.getContactSettings() } returns MutableStateFlow(emptyMap())

        viewModel =
            ContactsViewModel(
                savedStateHandle = SavedStateHandle(),
                nodeRepository = nodeRepository,
                packetRepository = packetRepository,
                snackbarManager = SnackbarManager(),
                radioConfigRepository = radioConfigRepository,
                connectionStateProvider = connectionStateProvider,
            )

        viewModel.contactList.test {
            var list = awaitItem()
            if (list.isEmpty()) {
                list = awaitItem()
            }
            val contact = list.find { it.contactKey == "8!remote" }
            assertNotNull(contact)
            assertEquals("🎤 Voice note (0:01)", contact.lastMessageText)
            assertFalse(contact.lastMessageText?.contains("/data/user/0") == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contactList formats remote voice note preview cleanly with sender prefix and stripping file path`() =
        runTest(testDispatcher) {
            nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 12345))
            nodeRepository.setMyId(NodeAddress.ID_LOCAL)
            nodeRepository.setNodes(
                listOf(
                    Node(num = 12345, user = User(id = NodeAddress.ID_LOCAL, short_name = "ME")),
                    Node(num = 67890, user = User(id = "8!remote", short_name = "Alice", long_name = "Alice")),
                ),
            )

            val voicePacket =
                DataPacket(
                    to = NodeAddress.ID_LOCAL,
                    channel = 0,
                    text =
                    "[Voice Note: 0:04 | /data/user/0/com.geeksville.mesh.fdroid.debug/cache/audio_remote.wt]",
                )
                    .apply {
                        from = "8!remote"
                        time = 1000L
                    }

            every { packetRepository.getContacts() } returns MutableStateFlow(mapOf("8!remote" to voicePacket))
            every { packetRepository.getContactSettings() } returns MutableStateFlow(emptyMap())

            viewModel =
                ContactsViewModel(
                    savedStateHandle = SavedStateHandle(),
                    nodeRepository = nodeRepository,
                    packetRepository = packetRepository,
                    snackbarManager = SnackbarManager(),
                    radioConfigRepository = radioConfigRepository,
                    connectionStateProvider = connectionStateProvider,
                )

            viewModel.contactList.test {
                var list = awaitItem()
                if (list.isEmpty()) {
                    list = awaitItem()
                }
                val contact = list.find { it.contactKey == "8!remote" }
                assertNotNull(contact)
                assertEquals("Alice: 🎤 Voice note (0:04)", contact.lastMessageText)
                assertFalse(contact.lastMessageText?.contains("/data/user/0") == true)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `contactList formats normal message preview without alteration`() = runTest(testDispatcher) {
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 12345))
        nodeRepository.setMyId(NodeAddress.ID_LOCAL)
        nodeRepository.setNodes(
            listOf(
                Node(num = 12345, user = User(id = NodeAddress.ID_LOCAL, short_name = "ME")),
                Node(num = 67890, user = User(id = "8!remote", short_name = "Alice", long_name = "Alice")),
            ),
        )

        val textPacket =
            DataPacket(to = NodeAddress.ID_LOCAL, channel = 0, text = "Hello mesh network!").apply {
                from = "8!remote"
                time = 1000L
            }

        every { packetRepository.getContacts() } returns MutableStateFlow(mapOf("8!remote" to textPacket))
        every { packetRepository.getContactSettings() } returns MutableStateFlow(emptyMap())

        viewModel =
            ContactsViewModel(
                savedStateHandle = SavedStateHandle(),
                nodeRepository = nodeRepository,
                packetRepository = packetRepository,
                snackbarManager = SnackbarManager(),
                radioConfigRepository = radioConfigRepository,
                connectionStateProvider = connectionStateProvider,
            )

        viewModel.contactList.test {
            var list = awaitItem()
            if (list.isEmpty()) {
                list = awaitItem()
            }
            val contact = list.find { it.contactKey == "8!remote" }
            assertNotNull(contact)
            assertEquals("Alice: Hello mesh network!", contact.lastMessageText)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
