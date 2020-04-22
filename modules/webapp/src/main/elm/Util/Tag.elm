module Util.Tag exposing (makeDropdownModel)

import Api.Model.Tag exposing (Tag)
import Comp.Dropdown
import Util.Maybe


makeDropdownModel : Comp.Dropdown.Model Tag
makeDropdownModel =
    Comp.Dropdown.makeModel
        { multiple = True
        , searchable = \n -> n > 4
        , makeOption = \tag -> { value = tag.id, text = tag.name }
        , labelColor =
            \tag ->
                if Util.Maybe.nonEmpty tag.category then
                    "basic blue"

                else
                    ""
        , placeholder = "Choose a tag…"
        }
