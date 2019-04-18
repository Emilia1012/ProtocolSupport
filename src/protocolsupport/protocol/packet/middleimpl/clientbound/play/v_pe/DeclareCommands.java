package protocolsupport.protocol.packet.middleimpl.clientbound.play.v_pe;

import io.netty.buffer.ByteBuf;
import protocolsupport.protocol.ConnectionImpl;
import protocolsupport.protocol.packet.middle.clientbound.play.MiddleDeclareCommands;
import protocolsupport.protocol.packet.middleimpl.ClientBoundPacketData;
import protocolsupport.protocol.serializer.ArraySerializer;
import protocolsupport.protocol.serializer.StringSerializer;
import protocolsupport.protocol.serializer.VarNumberSerializer;
import protocolsupport.protocol.typeremapper.pe.PEPacketIDs;
import protocolsupport.utils.Utils;
import protocolsupport.utils.recyclable.RecyclableCollection;
import protocolsupport.utils.recyclable.RecyclableSingletonList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeclareCommands extends MiddleDeclareCommands {

	private static final byte FLAG_IS_LITERAL = 1;
	private static final byte FLAG_IS_ARGUMENT = 2;
	private static final byte FLAG_IS_COMMAND_PATH_END = 4;
	private static final byte FLAG_HAS_REDIRECT = 8;
	private static final byte FLAG_HAS_SUGGESTION = 16;

	private static final byte FLAG_HAS_MIN_VALUE = 1;
	private static final byte FLAG_HAS_MAX_VALUE = 2;

	private static final byte STRING_IS_WORD = 1;
	private static final byte STRING_IS_PHRASE = 2;
	private static final byte STRING_IS_GREEDY = 3;

	private static final byte FLAG_ENTITY_AMOUNT_IS_SINGLE = 1;
	private static final byte FLAG_ENTITY_TYPE_IS_PLAYER = 2;

	// PE argument types
	public static final int ARG_TYPE_INT = 1;
	public static final int ARG_TYPE_FLOAT = 2;
	public static final int ARG_TYPE_VALUE = 3;
	public static final int ARG_TYPE_WILDCARD_INT = 4;
	public static final int ARG_TYPE_OPERATOR = 5;
	public static final int ARG_TYPE_TARGET = 6;
	public static final int ARG_TYPE_WILDCARD_TARGET = 7;

	public static final int ARG_TYPE_FILE_PATH = 15;

	public static final int ARG_TYPE_INT_RANGE = 19;

	public static final int ARG_TYPE_STRING = 28;
	public static final int ARG_TYPE_POSITION = 30;

	public static final int ARG_TYPE_MESSAGE = 33;
	public static final int ARG_TYPE_RAWTEXT = 35;
	public static final int ARG_TYPE_JSON = 38;
	public static final int ARG_TYPE_COMMAND = 45;

	// 0 unknown
	//     public static final int ARG_TYPE_INT = 1;
	//    public static final int ARG_TYPE_FLOAT = 2;
	//    public static final int ARG_TYPE_VALUE = 3;
	//    public static final int ARG_TYPE_WILDCARD_INT = 4;
	//    public static final int ARG_TYPE_OPERATOR = 5;
	//    public static final int ARG_TYPE_TARGET = 6;
	//    public static final int ARG_TYPE_WILDCARD_TARGET = 7;
	// 8-13 wildcard target
	// 14-17 file path
	//    public static final int ARG_TYPE_FILE_PATH = 15;
	// 18 unknown-26
	//    public static final int ARG_TYPE_INT_RANGE = 19; FEL UNKNOWN
	// 27 string
	//    public static final int ARG_TYPE_STRING = 28; också..?
	// 29-31 pos xyz
	//    public static final int ARG_TYPE_POSITION = 30;
	// 32 msg
	//    public static final int ARG_TYPE_MESSAGE = 33;
	// 34-36 text
	//    public static final int ARG_TYPE_RAWTEXT = 35;
	// 37-43 json
	//    public static final int ARG_TYPE_JSON = 38;
	// 44 cmnd--99
	//    public static final int ARG_TYPE_COMMAND = 45;

	private CommandNode[] allNodes;
	private int rootNodeIndex;

	public DeclareCommands(ConnectionImpl connection) {
		super(connection);
	}

	public static class CommandNode {
		private String name;
		private String argType;
		private String suggestion;
		private int[] children;
		private int redirect;
		private boolean isPathEnd;

		public CommandNode(String name, String argType, String suggestion, int[] children, int redirect, boolean isPathEnd) {
			this.name = name;
			this.argType = argType;
			this.suggestion = suggestion;
			this.children = children;
			this.redirect = redirect;
			this.isPathEnd = isPathEnd;
		}

		@Override
		public String toString() {
			return Utils.toStringAllFields(this);
		}

		public boolean isLeaf() {
			return this.children.length == 0;
		}
	}

	public static class PECommandNode {
		private String name;
		private String argType;
		private int nameIndex;

		public PECommandNode(String name, String argType, int nameIndex) {
			this.name = name;
			this.argType = argType;
			this.nameIndex = nameIndex;
		}
	}


		@Override
	public void readFromServerData(ByteBuf from) {
		// In theory, we could read this in the superclass. However, right now only PE needs this data, so save us
		// the trouble of parsing it for everyone else by doing it here.
		int length = VarNumberSerializer.readVarInt(from);

		allNodes = new CommandNode[length];
		for (int i = 0; i < length; i++) {
			CommandNode node = readCommandNode(from);
			allNodes[i] = node;
		}

		rootNodeIndex = VarNumberSerializer.readVarInt(from);
	}

	private CommandNode readCommandNode(ByteBuf from) {
		byte flags = from.readByte();
		boolean isPathEnd;
		int redirect;
		int[] children = ArraySerializer.readVarIntVarIntArray(from).clone();
		if ((flags & FLAG_HAS_REDIRECT) != 0) {
			redirect = VarNumberSerializer.readVarInt(from);
		} else {
			redirect = -1;
		}
		String name;
		String argType;
		String suggestion;

		isPathEnd = ((flags & FLAG_IS_COMMAND_PATH_END) != 0);

		if ((flags & FLAG_IS_LITERAL) != 0) {
			name = StringSerializer.readVarIntUTF8String(from);
			argType = null; // no argType signals this is a literal
			suggestion = null;
		} else if ((flags & FLAG_IS_ARGUMENT) != 0) {
			name = StringSerializer.readVarIntUTF8String(from);
			argType = readArgType(from);

			if ((flags & FLAG_HAS_SUGGESTION) != 0) {
				suggestion = StringSerializer.readVarIntUTF8String(from);
			} else {
				suggestion = null;
			}
		} else {
			// This is only allowed for the root node
			name = null;
			argType = null;
			suggestion = null;
		}
		return new CommandNode(name, argType, suggestion, children, redirect, isPathEnd);
	}

	private String readArgType(ByteBuf from) {
		String argType = StringSerializer.readVarIntUTF8String(from);
		// Depending on argType, there might be additional data.
		// At this point, we're just throwing this away, but we need at least
		// skip over it.

		if (argType.equals("brigadier:string")) {
			// Determine kind of string, any of STRING_IS_*...
			int stringType = VarNumberSerializer.readVarInt(from);
		} else if (argType.equals("brigadier:integer")) {
			byte flag = from.readByte();
			int min = -2147483648;
			int max = 2147483647;
			if ((flag & FLAG_HAS_MIN_VALUE) != 0) {
				min = from.readInt();
			}
			if ((flag & FLAG_HAS_MAX_VALUE) != 0) {
				max = from.readInt();
			}
		} else if (argType.equals("brigadier:float")) {
			byte flag = from.readByte();
			float min = -3.4028235E38F;
			float max = 3.4028235E38F;
			if ((flag & FLAG_HAS_MIN_VALUE) != 0) {
				min = from.readFloat();
			}
			if ((flag & FLAG_HAS_MAX_VALUE) != 0) {
				max = from.readFloat();
			}
		} else if (argType.equals("brigadier:double")) {
			byte flag = from.readByte();
			double min = -3.4028235E38F;
			double max = 3.4028235E38F;
			if ((flag & FLAG_HAS_MIN_VALUE) != 0) {
				min = from.readDouble();
			}
			if ((flag & FLAG_HAS_MAX_VALUE) != 0) {
				max = from.readDouble();
			}
		} else if (argType.equals("minecraft:entity")) {
			// The flag determines the amount (single or double) and type (players or entities)
			// See FLAG_ENTITY_AMOUNT_IS_SINGLE and FLAG_ENTITY_TYPE_IS_PLAYER.
			byte flag = from.readByte();
		} else if (argType.equals("minecraft:score_holder")) {
			// The "multiple" boolean is true if multiple, false if single.
			byte multiple = from.readByte();
		} else {
			// For all other types, there is no additional data
		}
		return argType;
	}

	private int getNumTopLevelNodes() {
		return allNodes[rootNodeIndex].children.length;
	}

	private CommandNode getTopLevelNode(int index) {
		int[] topLevelNodeIndices = allNodes[rootNodeIndex].children;
		return allNodes[topLevelNodeIndices[index]];
	}

	private int getPeVariableCode(String pcVariableName) {
		int peVariableCode;
		if (pcVariableName.equals("brigadier:bool")) {
			peVariableCode = 27;
		} else if (pcVariableName.equals("brigadier:float")) {
			peVariableCode = ARG_TYPE_FLOAT;
		} else if (pcVariableName.equals("brigadier:double")) {
			peVariableCode = ARG_TYPE_FLOAT;
		} else if (pcVariableName.equals("brigadier:integer")) {
			peVariableCode = ARG_TYPE_INT;
		} else if (pcVariableName.equals("brigadier:string")) {
			peVariableCode = 27;
		} else if (pcVariableName.equals("minecraft:int_range")) {
			peVariableCode = ARG_TYPE_INT;
		} else if (pcVariableName.equals("minecraft:float_range")) {
			peVariableCode = ARG_TYPE_FLOAT;
		} else if (pcVariableName.equals("minecraft:block_pos")) {
			peVariableCode = 29;
		} else if (pcVariableName.equals("minecraft:vec3")) {
			peVariableCode = 29;
		} else if (pcVariableName.equals("minecraft:entity")) {
			peVariableCode = ARG_TYPE_TARGET;
		} else if (pcVariableName.equals("minecraft:message")) {
			peVariableCode = 32;
		} else {
			peVariableCode = 34;
		}

		return peVariableCode;
	}
	void walkNode(List<List<PECommandNode>> overloads, Map<String, Integer> enumIndex, CommandNode currentNode, List<CommandNode> previousNodes) {
		if (currentNode.isLeaf()) {
			List<PECommandNode> newOverload = new ArrayList<>();
			for (CommandNode node : previousNodes) {
				// FIXME: We should really add all PE data here
				Integer enumPos = enumIndex.get(node.name);
				int index;
				if (enumPos == null) {
					index = enumIndex.size();
					enumIndex.put(node.name, index);
				} else {
					index = enumPos;
				}
				PECommandNode peNode = new PECommandNode(node.name, node.argType, index);
				newOverload.add(peNode);
			}
			overloads.add(newOverload);
		} else {
			for (int i = 0; i < currentNode.children.length; i++) {
				int childNodeIndex = currentNode.children[i];
				CommandNode childNode = allNodes[childNodeIndex];
				List<CommandNode> nodes = new ArrayList<>(previousNodes);
				nodes.add(childNode);

				walkNode(overloads, enumIndex, childNode, new ArrayList<>(nodes));
			}
		}
	}

	public ClientBoundPacketData create() {
		// Convert to flat PE structure
		Map<String, Integer> enumIndex = new HashMap<>();
		List<List<List<PECommandNode>>> allOverloads = new ArrayList<>(getNumTopLevelNodes());
		for (int i = 0; i < getNumTopLevelNodes(); i++) {
			CommandNode node = getTopLevelNode(i);

			List<List<PECommandNode>> overloads = new ArrayList<>();
				// HashSet<ArrayList<String>>();
			walkNode(overloads, enumIndex, node, new ArrayList<>());
			allOverloads.add(overloads);
		}

		// Convert enumIndex to proper array per index
		String[] enumArray = new String[enumIndex.size()-20];
		for (Map.Entry<String, Integer> entry : enumIndex.entrySet()) {
			if (entry.getValue() < enumIndex.size()-20) {
				enumArray[entry.getValue()] = entry.getKey();
			}
		}

		System.out.println("enum array length:" + enumArray.length);
		for (int i = 0; i  < enumArray.length; i++) {
			System.out.println("enum: " + i + ": " + enumArray[i]);
		}

		ClientBoundPacketData serializer = ClientBoundPacketData.create(PEPacketIDs.TAB_COMPLETE);
		// Write enumValues, a way to number strings
		// size
		VarNumberSerializer.writeVarInt(serializer, enumArray.length);

		// then one string per index
		for (String s : enumArray) {
			StringSerializer.writeVarIntUTF8String(serializer, s);
		}

		// Write "postfixes". Start with the count. We don't have any, so ignore the structure.
		VarNumberSerializer.writeVarInt(serializer, 0);

		// Write cmdEnums, a way to group the enumValues that can be refered to from
		// aliases, or from parameter types.
		// We have a 1-to-1 match between enums and enumGroups.
		// size
		System.out.println("IN TOTAL ENUMS: " + enumArray.length);
		VarNumberSerializer.writeVarInt(serializer, enumArray.length);
		for (int i = 0; i < enumArray.length; i++) {
			// Ignore name
			StringSerializer.writeVarIntUTF8String(serializer, enumArray[i] + "Enum");
			// Number of enums in group, always just 1.
			VarNumberSerializer.writeVarInt(serializer, 1);
			serializer.writeByte(i);
			//serializer.writeShort(i);
		}

		// Now process the actual commands. Write on per top-level ("command") node.
		VarNumberSerializer.writeVarInt(serializer, getNumTopLevelNodes());

		for (int i = 0; i < getNumTopLevelNodes(); i++) {
			CommandNode node = getTopLevelNode(i);

			System.out.println("NOW DOING COMMAND  " + node.name);
			StringSerializer.writeVarIntUTF8String(serializer, node.name);
			// PC does not have any description, so just send an empty string
			StringSerializer.writeVarIntUTF8String(serializer, "");
			serializer.writeByte(0); // Flags? Always 0.
			serializer.writeByte(0);  // Permissions? Always 0.

			// Enum index for our alias list, or -1 if none.
			serializer.writeIntLE(-1);

			// Write out all "overloads", i.e. all different ways to call this command with arguments.


			List<List<PECommandNode>> overloads = allOverloads.get(i);
			VarNumberSerializer.writeVarInt(serializer, overloads.size());

			for (List<PECommandNode> overload : overloads) {
				System.out.println("new overload, with num args: " + overload.size());
				VarNumberSerializer.writeVarInt(serializer, overload.size());

				for (PECommandNode peNode : overload) {
					// In theory, this is the index into the enumGroups, but we have the same index
					// to our single enum.
					int index = peNode.nameIndex;
					if (index >= (enumIndex.size()-20)) {
						index = 1;
					}
					if (peNode.argType != null) {
						// VAFRIABLE
						StringSerializer.writeVarIntUTF8String(serializer, peNode.name);
						//     public static final int ARG_FLAG_VALID = 0x100000;
						//    public static final int ARG_FLAG_ENUM = 0x200000;
//					flag = flag | 0x100000 | 0x200000;
						int flag = getPeVariableCode(peNode.argType) | 0x100000;

						serializer.writeIntLE(flag);
						System.out.println("variable: " + peNode.name);
					} else {
						// LITERAL
						StringSerializer.writeVarIntUTF8String(serializer, "'" + peNode.name + "'");
						//     public static final int ARG_FLAG_VALID = 0x100000;
						//    public static final int ARG_FLAG_ENUM = 0x200000;
//					flag = flag | 0x100000 | 0x200000;
						int flag = index | 0x100000 | 0x200000;

						serializer.writeIntLE(flag);
						System.out.println("literal: " + peNode.name);
					}
				//	StringSerializer.writeVarIntUTF8String(serializer,  enumArray[index] );
					System.out.println("sending " + enumArray[index]);
					//     public static final int ARG_FLAG_VALID = 0x100000;
					//    public static final int ARG_FLAG_ENUM = 0x200000;
//					flag = flag | 0x100000 | 0x200000;
					int flag = ARG_TYPE_INT | 0x100000;

			//		serializer.writeIntLE(flag);
					//     byte : is optional (1 = true, 0 = false)
					serializer.writeByte(0);
					serializer.writeByte(0); // Flags? Always 0.
				}
			}
/*
			System.out.println("ALL overloads for " + node.name + " in total " + overloads.size());
			for (List<Integer> overload : overloads) {
				String argsString = "";
				for (Integer index : overload) {
					if (index >= (enumIndex.size()-20)) {
						index = 1;
					}

					// For enums, name does not matter?
					StringSerializer.writeVarIntUTF8String(serializer, "");

					String arg = enumArray[index];
					argsString += arg + " ";
				}
				System.out.println(" * " + argsString);
			} */

			/*

			// we must always have a void overload, and our hack tried to make a single
			// overload from first child otherwise
			VarNumberSerializer.writeVarInt(serializer, 1);

			LinkedList<String> names = new LinkedList<>();
			LinkedList<String> argTypes = new LinkedList<>();
			LinkedList<Boolean> isLast = new LinkedList<>();


			while (node.children.length > 0) {
				// just get first node
				node = allNodes[node.children[0]];
				if (node.argType != null) {
					// it's an argument type, use it
					names.add(node.name);
					argTypes.add(node.argType);
				} else {
					names.add(node.name);
					argTypes.add("LITERAL");
				}
				isLast.add(node.isPathEnd);
			}

			// --- VarInt : length of parameters
			VarNumberSerializer.writeVarInt(serializer, names.size());
			for (int j = 0; j < names.size(); j++) {

				String argType = argTypes.get(j);
				int flag;
				if (argType.equals("LITERAL")) {
					flag = 27;
				} else if (argType.equals("brigadier:bool")) {
					flag = 27;
				} else if (argType.equals("brigadier:float")) {
					flag = ARG_TYPE_FLOAT;
				} else if (argType.equals("brigadier:double")) {
					flag = ARG_TYPE_FLOAT;
				} else if (argType.equals("brigadier:integer")) {
					flag = ARG_TYPE_INT;
				} else if (argType.equals("brigadier:string")) {
					flag = 27;
				} else if (argType.equals("minecraft:int_range")) {
					flag = ARG_TYPE_INT;
				} else if (argType.equals("minecraft:float_range")) {
					flag = ARG_TYPE_FLOAT;
				} else if (argType.equals("minecraft:block_pos")) {
					flag = 29;
				} else if (argType.equals("minecraft:vec3")) {
					flag = 29;
				} else if (argType.equals("minecraft:entity")) {
					flag = ARG_TYPE_TARGET;
				} else if (argType.equals("minecraft:message")) {
					flag = 32;
				} else {
					flag = 34;
				}

				if (argType.equals("LITERAL")) {
					StringSerializer.writeVarIntUTF8String(serializer, "'" + names.get(j) + "'");
					Integer index = enumIndex.get(names.get(j));
					int index2 = index;
					System.out.println("fuck is this: " + index + " but " + index2);
					if (index2 < (enumIndex.size()-20)) {
						System.out.println("processing literal " + names.get(j) + " at index " + index2 + ", found:" + enumArray[index2]);
					} else {
						index2 = 1;
						System.out.println("SKIPPING literal " + names.get(j) + " at index " + index2);
					}
					flag = index2 | 0x200000;
				} else {
					StringSerializer.writeVarIntUTF8String(serializer, names.get(j));
					System.out.println("processing variabl " + names.get(j) + " with flags " + flag);
				}

				//     public static final int ARG_FLAG_VALID = 0x100000;
				//    public static final int ARG_FLAG_ENUM = 0x200000;
				flag = flag | 0x100000;
				serializer.writeIntLE(flag);
				//     byte : is optional (1 = true, 0 = false)
				serializer.writeByte(0);
				serializer.writeByte(0); // Flags? Always 0.
			}
			*/
		}

		// "Soft enums". First write number of "soft enums". We never have any, so no further data is needed.
		VarNumberSerializer.writeVarInt(serializer, 0);

		return serializer;
	}

	@Override
	public RecyclableCollection<ClientBoundPacketData> toData() {
		return RecyclableSingletonList.create(create());
	}
}

/*
TODO:
fix aliases. PC has redirect, PE has an EnumSet as alias.

It could be that these Enums correspond to special types..?
NAME: itemType
values.size(): 0

NAME: enchantmentType
values.size(): 0

Does Enum work with empty names, or do they need to be unique?
Enum writer:
        if (enumValues.size() < 256) {
            indexWriter = WRITE_BYTE;
        } else if (enumValues.size() < 65536) {
            indexWriter = WRITE_SHORT;
        } else {
            indexWriter = WRITE_INT;
        }
 */

/*
ArgumentRegistry.a(new MinecraftKey("brigadier:bool"), BoolArgumentType.class, new ArgumentSerializerVoid(BoolArgumentType::bool));
ArgumentRegistry.a(new MinecraftKey("brigadier:float"), FloatArgumentType.class, new ArgumentSerializerFloat());
ArgumentRegistry.a(new MinecraftKey("brigadier:double"), DoubleArgumentType.class, new ArgumentSerializerDouble());
ArgumentRegistry.a(new MinecraftKey("brigadier:integer"), IntegerArgumentType.class, new ArgumentSerializerInteger());
ArgumentRegistry.a(new MinecraftKey("brigadier:string"), StringArgumentType.class, new ArgumentSerializerString());

a(new MinecraftKey("minecraft:entity"), ArgumentEntity.class, new net.minecraft.server.v1_13_R2.ArgumentEntity.a());
a(new MinecraftKey("minecraft:score_holder"), ArgumentScoreholder.class, new c());
a(new MinecraftKey("minecraft:int_range"), b.class, new net.minecraft.server.v1_13_R2.ArgumentCriterionValue.b.a());
a(new MinecraftKey("minecraft:float_range"), net.minecraft.server.v1_13_R2.ArgumentCriterionValue.a.class, new net.minecraft.server.v1_13_R2.ArgumentCriterionValue.a.a());

a(new MinecraftKey("minecraft:game_profile"), ArgumentProfile.class, new ArgumentSerializerVoid(ArgumentProfile::a));
a(new MinecraftKey("minecraft:block_pos"), ArgumentPosition.class, new ArgumentSerializerVoid(ArgumentPosition::a));
a(new MinecraftKey("minecraft:column_pos"), ArgumentVec2I.class, new ArgumentSerializerVoid(ArgumentVec2I::a));
a(new MinecraftKey("minecraft:vec3"), ArgumentVec3.class, new ArgumentSerializerVoid(ArgumentVec3::a));
a(new MinecraftKey("minecraft:vec2"), ArgumentVec2.class, new ArgumentSerializerVoid(ArgumentVec2::a));
a(new MinecraftKey("minecraft:block_state"), ArgumentTile.class, new ArgumentSerializerVoid(ArgumentTile::a));
a(new MinecraftKey("minecraft:block_predicate"), ArgumentBlockPredicate.class, new ArgumentSerializerVoid(ArgumentBlockPredicate::a));
a(new MinecraftKey("minecraft:item_stack"), ArgumentItemStack.class, new ArgumentSerializerVoid(ArgumentItemStack::a));
a(new MinecraftKey("minecraft:item_predicate"), ArgumentItemPredicate.class, new ArgumentSerializerVoid(ArgumentItemPredicate::a));
a(new MinecraftKey("minecraft:color"), ArgumentChatFormat.class, new ArgumentSerializerVoid(ArgumentChatFormat::a));
a(new MinecraftKey("minecraft:component"), ArgumentChatComponent.class, new ArgumentSerializerVoid(ArgumentChatComponent::a));
a(new MinecraftKey("minecraft:message"), ArgumentChat.class, new ArgumentSerializerVoid(ArgumentChat::a));
a(new MinecraftKey("minecraft:nbt"), ArgumentNBTTag.class, new ArgumentSerializerVoid(ArgumentNBTTag::a));
a(new MinecraftKey("minecraft:nbt_path"), ArgumentNBTKey.class, new ArgumentSerializerVoid(ArgumentNBTKey::a));
a(new MinecraftKey("minecraft:objective"), ArgumentScoreboardObjective.class, new ArgumentSerializerVoid(ArgumentScoreboardObjective::a));
a(new MinecraftKey("minecraft:objective_criteria"), ArgumentScoreboardCriteria.class, new ArgumentSerializerVoid(ArgumentScoreboardCriteria::a));
a(new MinecraftKey("minecraft:operation"), ArgumentMathOperation.class, new ArgumentSerializerVoid(ArgumentMathOperation::a));
a(new MinecraftKey("minecraft:particle"), ArgumentParticle.class, new ArgumentSerializerVoid(ArgumentParticle::a));
a(new MinecraftKey("minecraft:rotation"), ArgumentRotation.class, new ArgumentSerializerVoid(ArgumentRotation::a));
a(new MinecraftKey("minecraft:scoreboard_slot"), ArgumentScoreboardSlot.class, new ArgumentSerializerVoid(ArgumentScoreboardSlot::a));
a(new MinecraftKey("minecraft:swizzle"), ArgumentRotationAxis.class, new ArgumentSerializerVoid(ArgumentRotationAxis::a));
a(new MinecraftKey("minecraft:team"), ArgumentScoreboardTeam.class, new ArgumentSerializerVoid(ArgumentScoreboardTeam::a));
a(new MinecraftKey("minecraft:item_slot"), ArgumentInventorySlot.class, new ArgumentSerializerVoid(ArgumentInventorySlot::a));
a(new MinecraftKey("minecraft:resource_location"), ArgumentMinecraftKeyRegistered.class, new ArgumentSerializerVoid(ArgumentMinecraftKeyRegistered::a));
a(new MinecraftKey("minecraft:mob_effect"), ArgumentMobEffect.class, new ArgumentSerializerVoid(ArgumentMobEffect::a));
a(new MinecraftKey("minecraft:function"), ArgumentTag.class, new ArgumentSerializerVoid(ArgumentTag::a));
a(new MinecraftKey("minecraft:entity_anchor"), ArgumentAnchor.class, new ArgumentSerializerVoid(ArgumentAnchor::a));
a(new MinecraftKey("minecraft:item_enchantment"), ArgumentEnchantment.class, new ArgumentSerializerVoid(ArgumentEnchantment::a));
a(new MinecraftKey("minecraft:entity_summon"), ArgumentEntitySummon.class, new ArgumentSerializerVoid(ArgumentEntitySummon::a));
a(new MinecraftKey("minecraft:dimension"), ArgumentDimension.class, new ArgumentSerializerVoid(ArgumentDimension::a));
*/