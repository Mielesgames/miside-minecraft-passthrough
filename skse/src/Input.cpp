#include "Game.h"

namespace skycraft
{
	namespace
	{
		// DirectInput scan code (what Skyrim reports) -> SDL scancode / USB HID usage (what MC uses).
		constexpr auto kDikToSdl = [] {
			std::array<std::uint16_t, 256> t{};
			t[0x01] = 41;  // Esc
			for (int i = 0; i < 9; ++i) t[0x02 + i] = static_cast<std::uint16_t>(30 + i);  // 1-9
			t[0x0B] = 39;  // 0
			t[0x0C] = 45, t[0x0D] = 46, t[0x0E] = 42, t[0x0F] = 43;  // - = Backspace Tab
			t[0x10] = 20, t[0x11] = 26, t[0x12] = 8, t[0x13] = 21, t[0x14] = 23;  // Q W E R T
			t[0x15] = 28, t[0x16] = 24, t[0x17] = 12, t[0x18] = 18, t[0x19] = 19;  // Y U I O P
			t[0x1A] = 47, t[0x1B] = 48, t[0x1C] = 40, t[0x1D] = 224;               // [ ] Enter LCtrl
			t[0x1E] = 4, t[0x1F] = 22, t[0x20] = 7, t[0x21] = 9, t[0x22] = 10;     // A S D F G
			t[0x23] = 11, t[0x24] = 13, t[0x25] = 14, t[0x26] = 15;                // H J K L
			t[0x27] = 51, t[0x28] = 52, t[0x29] = 53, t[0x2A] = 225, t[0x2B] = 49;  // ; ' ` LShift backslash
			t[0x2C] = 29, t[0x2D] = 27, t[0x2E] = 6, t[0x2F] = 25, t[0x30] = 5;    // Z X C V B
			t[0x31] = 17, t[0x32] = 16, t[0x33] = 54, t[0x34] = 55, t[0x35] = 56;  // N M , . /
			t[0x36] = 229, t[0x37] = 85, t[0x38] = 226, t[0x39] = 44, t[0x3A] = 57;  // RShift KP* LAlt Space Caps
			for (int i = 0; i < 10; ++i) t[0x3B + i] = static_cast<std::uint16_t>(58 + i);  // F1-F10
			t[0x45] = 83, t[0x46] = 71;                                             // NumLock ScrollLock
			t[0x47] = 95, t[0x48] = 96, t[0x49] = 97, t[0x4A] = 86;                 // KP7 KP8 KP9 KP-
			t[0x4B] = 92, t[0x4C] = 93, t[0x4D] = 94, t[0x4E] = 87;                 // KP4 KP5 KP6 KP+
			t[0x4F] = 89, t[0x50] = 90, t[0x51] = 91, t[0x52] = 98, t[0x53] = 99;   // KP1 KP2 KP3 KP0 KP.
			t[0x56] = 100, t[0x57] = 68, t[0x58] = 69;                              // OEM102 F11 F12
			t[0x9C] = 88, t[0x9D] = 228, t[0xB5] = 84, t[0xB7] = 70, t[0xB8] = 230;  // KPEnter RCtrl KP/ PrtSc RAlt
			t[0xC5] = 72, t[0xC7] = 74, t[0xC8] = 82, t[0xC9] = 75, t[0xCB] = 80;  // Pause Home Up PgUp Left
			t[0xCD] = 79, t[0xCF] = 77, t[0xD0] = 81, t[0xD1] = 78, t[0xD2] = 73;  // Right End Down PgDn Insert
			t[0xD3] = 76, t[0xDB] = 227, t[0xDC] = 231, t[0xDD] = 101;             // Delete LWin RWin Menu
			return t;
		}();

		// Keys Skyrim keeps while Minecraft drives the player. Everything else is Minecraft's.
		constexpr std::uint32_t kDikEscape = 0x01;   // Skyrim journal / system menu (or closes an MC screen)
		constexpr std::uint32_t kDikConsole = 0x29;  // Skyrim console
		constexpr std::uint32_t kDikG = 0x22;        // Skyrim activate: doors, NPCs, containers, items
		constexpr std::uint32_t kDikH = 0x23;        // Skyrim wait (T is Minecraft chat)
		constexpr std::uint32_t kDikJ = 0x24;        // Skyrim quest journal
		constexpr std::uint32_t kDikM = 0x32;        // Skyrim map
		constexpr std::uint32_t kDikO = 0x18;        // Minecraft pause / options menu (Esc is Skyrim's)
		constexpr std::uint32_t kDikF9 = 0x43;       // Skyrim quickload

		bool IsSkyrimMenuKey(std::uint32_t a_code)
		{
			return a_code == kDikEscape || a_code == kDikConsole || a_code == kDikJ || a_code == kDikM || a_code == kDikF9;
		}

		float lookDx = 0.0f;
		float lookDy = 0.0f;

		// What asked Skyrim to activate its crosshair target.
		enum class ActivateBy
		{
			kG,      // the G key
			kClick,  // a right click Minecraft had no use for (proto::kEvActivate)
		};

		// A right click activates at most once per this many seconds of unpaused play.
		constexpr float kClickActivateInterval = 0.4f;
		float           clickCooldown = 0.0f;

		// G (or a right click Minecraft had no use for) on something Skyrim can activate (door, NPC,
		// container, item, furniture) activates it in Skyrim. Furniture (chairs, beds, crafting
		// stations, pull-bar levers) hands the player to Skyrim while it's used (Game.cpp,
		// SkyrimTakeover). The crosshair is read now, when the key or click arrives. True if there
		// was something to activate.
		bool ActivateSkyrimTarget(ActivateBy a_by)
		{
			auto* pick = RE::CrosshairPickData::GetSingleton();
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!pick || !player) {
				return false;
			}
			auto target = pick->GetActiveTarget().get();
			if (!target || target.get() == player) {
				if (a_by == ActivateBy::kG) {
					logger::info("G: nothing to activate under the crosshair");
				}
				return false;  // most right clicks land on nothing Skyrim can activate: stay quiet
			}
			target->ActivateRef(player, 0, nullptr, 0, false);
			logger::info("{}activated {:08X} ({})", a_by == ActivateBy::kClick ? "right click " : "", target->GetFormID(), target->GetDisplayFullName());
			return true;
		}

		void OpenWaitMenu()
		{
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!player || !player->CanSleepWait(nullptr)) {
				return;  // CanSleepWait already showed Skyrim's reason (enemies nearby, ...)
			}
			if (auto* queue = RE::UIMessageQueue::GetSingleton()) {
				queue->AddMessage(RE::SleepWaitMenu::MENU_NAME, RE::UI_MESSAGE_TYPE::kShow, nullptr);
			}
		}

		class InputSink final : public RE::BSTEventSink<RE::InputEvent*>
		{
		public:
			static InputSink* Get()
			{
				static InputSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>*) override
			{
				if (!a_event) {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto& st = State();
				auto& link = Link::Get();
				// Menus that pause the game stop the per-frame update, so check them here.
				const bool menuOpen = Game::SkyrimMenuOpen();
				if (menuOpen && !st.skyrimMenuOpen) {
					Input::ReleaseAll();
				}
				if (menuOpen) {
					st.skyrimMenuOpen = true;
				}
				// Minecraft's too while it shows its own world (View.cpp), Skyrim's player parked.
				const bool view = View::Active();
				const bool route = (st.puppeting || view) && !menuOpen;

				for (auto* e = *a_event; e; e = e->next) {
					switch (e->GetEventType()) {
					case RE::INPUT_EVENT_TYPE::kMouseMove:
						{
							if (!route) {
								break;
							}
							auto* mm = e->AsMouseMoveEvent();
							if (st.mcScreenOpen) {
								const int vw = std::max(1, st.viewportW.load()), vh = std::max(1, st.viewportH.load());
								const int x = std::clamp(st.cursorX.load() + mm->mouseInputX, 0, vw - 1);
								const int y = std::clamp(st.cursorY.load() + mm->mouseInputY, 0, vh - 1);
								st.cursorX = x;
								st.cursorY = y;
								// The cursor lives in viewport pixels; Minecraft's frames can be smaller (the
								// Minecraft view renders at most 1080 high).
								const int ow = st.overlayW.load(), oh = st.overlayH.load();
								link.PushInput(proto::kInCursor, 0, ow > 0 ? x * ow / vw : x, oh > 0 ? y * oh / vh : y);
							} else if (view) {
								// Minecraft turns its own player in another dimension (Skyrim's look is for the mirror).
								link.PushInput(proto::kInLook, 0, mm->mouseInputX, mm->mouseInputY);
							} else {
								lookDx += static_cast<float>(mm->mouseInputX);
								lookDy += static_cast<float>(mm->mouseInputY);
							}
							break;
						}
					case RE::INPUT_EVENT_TYPE::kButton:
						{
							auto* button = e->AsButtonEvent();
							const bool down = button->IsDown();
							const bool up = button->IsUp();
							if (!route || (!down && !up)) {
								break;
							}
							const auto code = button->GetIDCode();
							if (button->GetDevice() == RE::INPUT_DEVICE::kKeyboard) {
								// With a Minecraft screen up (chat, inventory, options) every key is
								// Minecraft's, so typing works and Esc closes the screen.
								if (!st.mcScreenOpen) {
									if (IsSkyrimMenuKey(code)) {
										break;  // MenuControls (see the hook below) opens Skyrim's menu
									}
									// (G and H are Minecraft's keys in the Minecraft view: nothing of Skyrim's is there.)
									if (code == kDikO || (!view && (code == kDikG || code == kDikH))) {
										if (down) {
											if (code == kDikG) {
												ActivateSkyrimTarget(ActivateBy::kG);
											} else if (code == kDikH) {
												OpenWaitMenu();
											} else {
												Input::ReleaseAll();
												link.PushInput(proto::kInOpenMenu, 0);
											}
										}
										break;
									}
								}
								if (const auto sdl = kDikToSdl[code & 0xFF]) {
									link.PushInput(proto::kInKey, sdl, down ? 1 : 0);
								}
							} else if (button->GetDevice() == RE::INPUT_DEVICE::kMouse) {
								if (code == 8 || code == 9) {
									if (down) {
										link.PushInput(proto::kInScroll, 0, code == 8 ? 120 : -120);
									}
									break;
								}
								static constexpr std::uint16_t kButtons[8] = { 1, 3, 2, 4, 5, 0, 0, 0 };
								const std::uint16_t sdlButton = code < 8 ? kButtons[code] : std::uint16_t(0);
								if (sdlButton) {
									link.PushInput(proto::kInMouseButton, sdlButton, down ? 1 : 0);
								}
							}
							break;
						}
					case RE::INPUT_EVENT_TYPE::kChar:
						if (route && st.mcScreenOpen) {
							link.PushInput(proto::kInText, 0, static_cast<std::int32_t>(e->AsCharEvent()->keyCode));
						}
						break;
					default:
						break;
					}
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// MenuControls opens Skyrim's menus (Tab, I, J, M, Wait, quicksave, ...). While Minecraft
		// drives the player only Esc, the console, M (map), J (journal) and F9 (quickload) reach
		// it (F5 is Minecraft's camera); while an MC screen is open nothing does.
		struct MenuControlsHook
		{
			static RE::BSEventNotifyControl thunk(RE::MenuControls* a_this, RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>* a_source)
			{
				auto& st = State();
				if (!a_event || !*a_event || !(st.puppeting || View::Active()) || st.skyrimMenuOpen || Game::SkyrimMenuOpen()) {
					return func(a_this, a_event, a_source);  // a Skyrim menu is up: all its input
				}
				std::vector<RE::InputEvent*> keep;
				std::vector<RE::InputEvent*> all;
				for (auto* e = *a_event; e; e = e->next) {
					all.push_back(e);
					if (st.mcScreenOpen) {
						continue;
					}
					if (e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && e->GetDevice() == RE::INPUT_DEVICE::kKeyboard) {
						if (IsSkyrimMenuKey(e->AsButtonEvent()->GetIDCode())) {
							keep.push_back(e);
						}
					}
				}
				if (keep.empty()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> savedNext;
				for (auto* e : all) {
					savedNext.push_back(e->next);
				}
				for (std::size_t i = 0; i < keep.size(); ++i) {
					keep[i]->next = i + 1 < keep.size() ? keep[i + 1] : nullptr;
				}
				RE::InputEvent* head = keep.front();
				const auto      result = func(a_this, &head, a_source);
				for (std::size_t i = 0; i < all.size(); ++i) {
					all[i]->next = savedNext[i];
				}
				return result;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// PlayerControls turns input into the Skyrim player's own actions: moving, looking,
		// attacking, jumping, sneaking, the camera. While Minecraft drives the player it gets none.
		// (Disabling Skyrim's player controls instead would also stop its NPCs fighting the player.)
		struct PlayerControlsHook
		{
			static RE::BSEventNotifyControl thunk(RE::PlayerControls* a_this, RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>* a_source)
			{
				if (!State().minecraftOwnsPlayer || Game::SkyrimMenuOpen()) {
					return func(a_this, a_event, a_source);
				}
				return RE::BSEventNotifyControl::kContinue;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	namespace Input
	{
		void Install()
		{
			if (auto* devices = RE::BSInputDeviceManager::GetSingleton()) {
				devices->AddEventSink(InputSink::Get());
			}
			REL::Relocation<std::uintptr_t> vtbl{ RE::VTABLE_MenuControls[0] };
			MenuControlsHook::func = vtbl.write_vfunc(0x1, MenuControlsHook::thunk);
			REL::Relocation<std::uintptr_t> playerVtbl{ RE::VTABLE_PlayerControls[0] };
			PlayerControlsHook::func = playerVtbl.write_vfunc(0x1, PlayerControlsHook::thunk);
			logger::info("input hooks installed");
		}

		void ConsumeLook(float& a_dx, float& a_dy)
		{
			a_dx = lookDx;
			a_dy = lookDy;
			lookDx = lookDy = 0.0f;
		}

		void ReleaseAll()
		{
			Link::Get().PushInput(proto::kInReleaseAll, 0);
		}

		void UpdateClickActivate(bool a_clicked, float a_delta)
		{
			// Counted in frames the game runs, so time spent in a menu that pauses it doesn't count:
			// the second click of a double click on a chest, read after its menu closes, is dropped.
			clickCooldown = std::max(0.0f, clickCooldown - std::max(a_delta, 0.0f));
			if (!a_clicked || clickCooldown > 0.0f || State().mcScreenOpen || Game::SkyrimMenuOpen()) {
				return;
			}
			if (ActivateSkyrimTarget(ActivateBy::kClick)) {
				clickCooldown = kClickActivateInterval;
			}
		}

		void SetActivatePromptKey(bool a_minecraftControls)
		{
			static std::uint16_t savedKey = 0xFFFF;
			auto*                controls = RE::ControlMap::GetSingleton();
			auto*                context = controls ? controls->controlMap[RE::UserEvents::INPUT_CONTEXT_ID::kGameplay] : nullptr;
			if (!context) {
				return;
			}
			for (auto& mapping : context->deviceMappings[RE::INPUT_DEVICE::kKeyboard]) {
				if (mapping.eventID != "Activate") {
					continue;
				}
				if (a_minecraftControls && savedKey == 0xFFFF) {
					savedKey = mapping.inputKey;
					mapping.inputKey = static_cast<std::uint16_t>(kDikG);
				} else if (!a_minecraftControls && savedKey != 0xFFFF) {
					mapping.inputKey = savedKey;
					savedKey = 0xFFFF;
				}
			}
		}
	}
}
