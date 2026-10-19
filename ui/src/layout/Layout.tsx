import React from "react";

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  return <div className="flex h-dvh min-h-0 flex-col overflow-hidden md:flex-row">{children}</div>;
};

export default Layout;
