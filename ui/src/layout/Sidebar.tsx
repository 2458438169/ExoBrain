import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div
      className="hidden h-full shrink-0 border-r border-slate-200 bg-slate-50 md:block"
      style={{
        width: "320px",
      }}
    >
      {children}
    </div>
  );
};

export default Sidebar;
