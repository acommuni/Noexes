package me.mdbell.noexs.code.opcode.manager;

import org.apache.commons.lang3.StringUtils;

import me.mdbell.noexs.code.OperationUtils;
import me.mdbell.noexs.code.model.ECodeMemoryRegion;
import me.mdbell.noexs.code.model.EDataType;
import me.mdbell.noexs.code.model.Keypad;
import me.mdbell.noexs.code.opcode.EOpCode;
import me.mdbell.noexs.code.opcode.OpCode5LoadRegisterWithMemory;
import me.mdbell.noexs.code.opcode.model.EArithmeticOperation;

// TODO faire disparaitre la classe

public class OpCodeOperationBuilder {

//	### Code Type 0x0: Store Static Value to Memory
//	Code type 0x0 allows writing a static value to a memory address.
//
//	#### Encoding
//	`0TMR00AA AAAAAAAA VVVVVVVV (VVVVVVVV)`
//
//	+ T: Width of memory write (1, 2, 4, or 8 bytes).
//	+ M: Memory region to write to (0 = Main NSO, 1 = Heap, 2 = Alias, 3 = Aslr).
//	+ R: Register to use as an offset from memory region base.
//	+ A: Immediate offset to use from memory region base.
//	+ V: Value to write.
//
//	---
    /**
     * 
     * @param dataType      T
     * @param region        M
     * @param registerToUse R
     * @param offset        A
     * @param value         V
     * @return
     */
    public static String storeStaticValueToMemory(EDataType dataType, ECodeMemoryRegion region, char registerToUse,
            String offset, String value) {
        String res = buildOperationHead(EOpCode.STORE_STATIC_VALUE_TO_MEMORY, dataType, region, registerToUse);
        res += "00";
        res += OperationUtils.padHexValue(offset, EDataType.ADDR);
        res += " " + OperationUtils.padHexValue(value, dataType);
        return res;
    }


    public static OpCode5LoadRegisterWithMemory loadRegisterWithMemoryValueFromFixedAddress(EDataType dataType,
            ECodeMemoryRegion region, String registerToUse, long offset) {
        return OpCode5LoadRegisterWithMemory.loadFromRegisterAddressEncoding(dataType, region, registerToUse, offset);
    }

    public static OpCode5LoadRegisterWithMemory loadRegisterWithMemoryValueFromRegisterAddress(EDataType dataType,
            String registerToUse, long offset) {
        return OpCode5LoadRegisterWithMemory.loadFromFixedAddressEncoding(dataType, registerToUse, offset);
    }

//	### Code Type 0x6: Store Static Value to Register Memory Address
//	Code type 0x6 allows writing a fixed value to a memory address specified by a register.
//	
//	#### Encoding
//	`6T0RIor0 VVVVVVVV VVVVVVVV`
//	
//	+ T: Width of memory write (1, 2, 4, or 8 bytes).
//	+ R: Register used as base memory address.
//	+ I: Increment register flag (0 = do not increment R, 1 = increment R by T).
//	+ o: Offset register enable flag (0 = do not add r to address, 1 = add r to address).
//	+ r: Register used as offset when o is 1.
//	+ V: Value to write to memory.
//	
//	---
    public static String storeStaticValueToRegisterMemoryAddress(EDataType dataType, String registerToUse,
            boolean incrementRegisterFlag, boolean offsetRegisterEnable, char registerToUseAsOffset, String hexValue) {
        String res = "" + EOpCode.STORE_STATIC_VALUE_TO_REGISTER_MEMORY_ADDRESS.getCodeType();
        res += dataType.getDataTypeCode();
        res += "0";
        res += registerToUse;
        res += getFlagValue(incrementRegisterFlag);
        res += getFlagValue(offsetRegisterEnable);
        res += getOptionalValue(offsetRegisterEnable, String.valueOf(registerToUseAsOffset), "0");
        res += "0";
        res += " " + OperationUtils.padHexValue(hexValue, dataType, 16);
        return res;

    }



//	Code Type 0x8: Begin Keypress Conditional Block
//
//	Code type 0x8 enters or skips a conditional block based on whether a key combination is pressed.
//	Encoding
//
//	8kkkkkkk
//
//	    k: Keypad mask to check against, see below.
//
//	Note that for multiple button combinations, the bitmasks should be ORd together.
//	Keypad Values
//
//	Note: This is the direct output of hidKeysDown().
//
//	    0000001: A
//	    0000002: B
//	    0000004: X
//	    0000008: Y
//	    0000010: Left Stick Pressed
//	    0000020: Right Stick Pressed
//	    0000040: L
//	    0000080: R
//	    0000100: ZL
//	    0000200: ZR
//	    0000400: Plus
//	    0000800: Minus
//	    0001000: Left
//	    0002000: Up
//	    0004000: Right
//	    0008000: Down
//	    0010000: Left Stick Left
//	    0020000: Left Stick Up
//	    0040000: Left Stick Right
//	    0080000: Left Stick Down
//	    0100000: Right Stick Left
//	    0200000: Right Stick Up
//	    0400000: Right Stick Right
//	    0800000: Right Stick Down
//	    1000000: SL
//	    2000000: SR

    public static String beginKeypressConditionalBlock(Keypad[] keypads) {
        String res = "" + EOpCode.BEGIN_KEYPRESS_CONDITIONAL_BLOCK.getCodeType();

        long mask = 0;
        for (Keypad keypad : keypads) {
            mask |= keypad.getKeypadMask();
        }
        res += OperationUtils.padHexValue(Long.toHexString(mask), EDataType.T32, 7);
        return res;
    }

    private static String getOptionalValue(boolean toBeSet, String value, String defaultValue) {
        String res;
        if (toBeSet) {
            res = value;
        } else {
            res = defaultValue;
        }
        return res;
    }

    private static String getFlagValue(boolean flag) {
        String res;
        if (flag) {
            res = "1";
        } else {
            res = "0";
        }
        return res;
    }

    private static String buildOperationHead(EOpCode op, EDataType dataType, ECodeMemoryRegion region,
            char registerToUse) {
        String regionStr = "0";
        if (region != null) {
            regionStr = Integer.toString(region.getPointerAdressType());
        }

        return StringUtils.join(op.getCodeType(), dataType.getDataTypeCode(), regionStr, registerToUse);
    }
}
