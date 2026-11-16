package com.marketsboard.ui

/**
 * The Glance contract, per presenter action. The safety rule is enforced in the PRESENTERS (not by hiding widgets): while
 * the truck moves, every action marked [Blocked] does nothing, whatever the UI sends. Only [Allowed] actions (closing or
 * going back, which cannot add anything) still work. The enums below are the single list of presenter actions: each
 * presenter routes every action through `guarded(Act.X) { ... }`, and a test enumerates the entries (no reflection),
 * calls each blocked one in Glance and asserts that nothing changed, that nothing was written and that nothing was fetched.
 * Adding an action means adding an entry here, which the exhaustive `when` in that test forces you to cover.
 */
enum class GlanceRule { Allowed, Blocked }

/** Every action of [com.marketsboard.ui.markets.MarketsPresenter]. */
enum class MarketsAct(val glance: GlanceRule = GlanceRule.Blocked) {
    Back(GlanceRule.Allowed),
    Refresh, Retry, Open,
    CloseDetail(GlanceRule.Allowed),
    SelectRange, RetryRange, ToggleEdit, Move, AskRename, RenameChange, RenameConfirm,
    RenameCancel(GlanceRule.Allowed),
    AskRemove, ConfirmRemove,
    CancelRemove(GlanceRule.Allowed),
    AddOpen, ReplaceOpen,
    AddClose(GlanceRule.Allowed),
    QueryChange, PickResult, SubmitTyped, AddAnyway,
}

/** Every action of [com.marketsboard.ui.news.NewsPresenter]. */
enum class NewsAct(val glance: GlanceRule = GlanceRule.Blocked) {
    Back(GlanceRule.Allowed),
    Refresh, Retry, SetFilter, Open, OpenLink,
    CloseDetail(GlanceRule.Allowed),
}

/** Every action of [com.marketsboard.ui.settings.SettingsPresenter]: the Settings tab is unreachable while driving, so all are blocked. */
enum class SettingsAct(val glance: GlanceRule = GlanceRule.Blocked) {
    SetPassenger, SetVoice, SetSpeechRate, SetIncludeNews, SetMediaKey, RequestLocation,
}
