package ai.neargo.sharehub.dev;

/** 充电宝健康（{@code dev_powerbank.health}）。AGED = 循环次数超限、待回收报废（批次 D2），不再借出。 */
public enum PowerbankHealth {
    OK, FAULT, AGED
}
