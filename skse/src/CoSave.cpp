#include "CoSave.h"

#include "Companion.h"
#include "Spawn.h"

namespace skycraft::CoSave
{
	namespace
	{
		constexpr std::uint32_t kSaveId = 0x53434450;  // 'SCDP'

		// Main thread: SKSE saves and loads there.
		void OnSave(SKSE::SerializationInterface* a_intfc)
		{
			Spawn::Save(a_intfc);
			Companion::Save(a_intfc);
		}

		void OnLoad(SKSE::SerializationInterface* a_intfc)
		{
			Spawn::Revert();
			Companion::Revert();
			std::uint32_t type = 0, version = 0, length = 0;
			while (a_intfc->GetNextRecordInfo(type, version, length)) {
				// Whatever a reader leaves unread, SKSE skips on the next GetNextRecordInfo.
				switch (type) {
				case Spawn::kSaveRecord:
					Spawn::Load(a_intfc, version);
					break;
				case Companion::kSaveRecord:
					Companion::Load(a_intfc, version);
					break;
				default:
					logger::warn("SkyCraft: the co-save has a record SkyCraft doesn't know ({:08X}, version {}, {} bytes); skipped", type, version, length);
					break;
				}
			}
		}

		void OnRevert(SKSE::SerializationInterface*)
		{
			Spawn::Revert();
			Companion::Revert();
		}
	}

	void Install()
	{
		const auto* serialization = SKSE::GetSerializationInterface();
		if (!serialization) {
			logger::warn("SkyCraft: no SKSE co-save; creatures from a saved game won't be taken away and tamed companions are forgotten after it's loaded");
			return;
		}
		serialization->SetUniqueID(kSaveId);
		serialization->SetSaveCallback(OnSave);
		serialization->SetLoadCallback(OnLoad);
		serialization->SetRevertCallback(OnRevert);
	}
}
